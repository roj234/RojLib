import {escapeHTML, HeightConfig, scheduler} from "./util.js";
import {INDEX, VirtualList} from "unconscious/common/VirtualList.js";

/**
 * 两侧比较文件
 * @param container 容器
 * @param original 左侧
 * @param modified 右侧
 * @param diffList 差异
 * @param isUnaligned 是否未对齐
 * @returns {{leftView: SidedViewer, mergeToLeft: mergeToLeft, mergeToRight: mergeToRight, rightView: SidedViewer}}
 */
function highlightSideBySide(container, original, modified, diffList, isUnaligned) {
	const svg = <svg:svg class="links"></svg:svg>;//E("svg", "http://www.w3.org/2000/svg");
	svg.className.baseVal = "links";
	container.append(svg);

	const updateSvg = scheduler(() => {
		svg.innerHTML = '';

		// 获取当前渲染范围
		const leftStart = leftView.content._start;
		const leftEnd = leftView.content._end;
		const rightStart = rightView.content._start;
		const rightEnd = rightView.content._end;

		const leftMin = rightView.lineDiffs[leftStart]?.[0].originalRange[0];
		const leftMax = leftView.lineDiffs[leftEnd]?.[0].originalRange[0];

		if (leftMax < rightView.lineDiffs[rightStart][0].originalRange[0]) return;
		if (leftView.lineDiffs[rightEnd]?.[0].originalRange[0] < leftMin) return;

		const map = new Map();

		for (let i = rightStart; i < rightEnd; i++) {
			let diffList = rightView.lineDiffs[i];
			if (!diffList) continue;
			if (diffList[diffList.length-1].originalRange[0] < leftMin) continue;
			if (diffList[0].originalRange[0] > leftMax) break;

			const diffValueR = rightView.content.getValue(i).querySelectorAll("span");
			let k = 1;
			for (let j = 0; j < diffList.length; j++) {
				const diff = diffList[j];
				if (diff.type === "equal") continue;
				map.set(diff, diffValueR[k++]);
				break; // 一行就一个
			}
		}

		for (let i = leftStart; i < leftEnd; i++) {
			let diffList = leftView.lineDiffs[i];
			if (!diffList) continue;
			const diffValueL = leftView.content.getValue(i).querySelectorAll("span");
			let k = 1;
			for (let j = 0; j < diffList.length; j++) {
				const diff = diffList[j];
				if (diff.type === "equal") continue;

				const leftSpan = diffValueL[k++];
				const rightSpan = map.get(diff);
				if (rightSpan) {
					map.delete(diff);
					// 坐标计算函数（考虑滚动偏移）
					const getPos = (el, isLeft) => {
						const rect = el.getBoundingClientRect();
						const containerRect = container.getBoundingClientRect();
						return {
							x: isLeft ? rect.right - containerRect.left : rect.left - containerRect.left,
							y: (rect.top + rect.bottom)/2 - containerRect.top
						};
					};

					// 绘制连接线
					const start = getPos(leftSpan, true);
					const end = getPos(rightSpan, false);

					const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
					const controlOffset = Math.abs(end.x - start.x) * 0.3; // 动态控制点

					path.setAttribute('d', `
                        M ${start.x},${start.y}
                        C ${start.x + controlOffset},${start.y}
                          ${end.x - controlOffset},${end.y}
                          ${end.x},${end.y}
                    `);

					path.classList.add('link', diff.type);
					svg.appendChild(path);

					break;
				}
			}
		}
	});

	// 列表实例
	class SidedViewer {
		constructor(container, text, diffList, isOriginal, isUnaligned) {
			this.container = container;
			this.isOriginal = isOriginal;
			this.diffClass = {
				replace: isOriginal ? 'replace' : 'modified',
				insert: isOriginal ? 'insert caret' : 'insert',
				delete: isOriginal ? 'delete' : 'delete caret'
			};

			if (isUnaligned && !isOriginal) {
			}

			this._text = text;
			this._diffList = diffList;

			// 转换数据
			this._mapDiffToLine(text, diffList);

			const box = <div class="text-box"></div>;
			container.append(box);

			this.content = new VirtualList({
				element: box,
				data: this.lines.map(() => {return {}}), // 存数据的空容器
				itemHeight: HeightConfig.lineHeight,
				height: HeightConfig.containerHeight,
				renderer: this._createLine.bind(this)
			});

			this._initSelectionHandlers();
		}

		_mapDiffToLine(text, diffList) {
			let lineText = [];
			let lineIndex = [];
			let lineDiff = [];

			const EMPTY = null;
			let i = 0, prevI = 0;
			let j = 0;
			while (i <= text.length) {
				if (i === text.length || '\n' === text.charAt(i)) {
					lineIndex.push(prevI);
					lineText.push(text.substring(prevI, i));

					prevI = ++i;

					const newDiff = [];
					for (; j < diffList.length; ) {
						const diff = diffList[j];
						const range = this.isOriginal ? diff.originalRange : diff.modifiedRange;
						newDiff.push(diff);
						if (range[1] <= i) j++;
						if (range[1] >= i) break;
					}
					lineDiff.push(newDiff.length ? newDiff : EMPTY);
				} else {
					i++;
				}
			}

			this.lines = lineText;
			this.lineOffset = lineIndex;
			this.lineDiffs = lineDiff;
		}

		_createLine(_, lineNum) {
			updateSvg();

			const line = this.lines[lineNum];
			const diffs = this.lineDiffs[lineNum];
			const lineStart = this.lineOffset[lineNum];
			const lineEnd = this.lineOffset[lineNum + 1] || line.length + lineStart;
			let pos = 0;

			let content = '';
			let has_both = 0;

			// 生成高亮片段
			if (diffs != null) diffs.forEach(diff => {
				const range = this.isOriginal ? diff.originalRange : diff.modifiedRange;
				const start = Math.max(lineStart, range[0]);
				const end = Math.min(lineEnd, range[1]);

				// 添加前面的普通文本
				if (start > lineStart + pos) content += line.slice(pos, pos = start - lineStart);

				// 添加高亮部分
				const className = this.diffClass[diff.type];
				has_both |= className ? 1 : 0;

				const text = line.slice(pos, end - lineStart);
				content += !className ? escapeHTML(text) : `<span class="${className}">${escapeHTML(text)}</span>`;
				pos = end - lineStart;
			});

			// 添加剩余文本
			if (pos < line.length) content += line.slice(pos);

			const lineEl = <div></div>;
			lineEl._diff = diffs;
			lineEl.title = diffs?.length+" diffs rendered";
			// 奇偶背景
			lineEl.className = (lineNum&1) ? 'line' : 'line odd';
			if (has_both) lineEl.classList.add("changed");
			lineEl.innerHTML = `<span class="line-number">${lineNum+1}</span>`+content;

			return lineEl;
		}

		//region selection & copy
		_initSelectionHandlers() {
			this.selection = {
				start: null,
				end: null,
				isSelecting: false
			};

			this.content._wrapper.addEventListener('mousedown', () => {
				this._clearSelection();
				this.selection.isSelecting = true;
			});
			this.content._wrapper.addEventListener('mousemove', this._handleMouseMove);
			document.addEventListener('mouseup', this._handleMouseUp);
			document.addEventListener('copy', this._handleCopy);

			// 添加滚动事件监听
			this.content._wrapper.addEventListener('scroll', scheduler(this._restoreSelection));
		}

		_clearSelection() {
			this.selection.start = this.selection.end = null;
		}

		_handleMouseMove = () => {
			if (this.selection.isSelecting && !this.selection.start) {
				// 获取初始选区信息
				const sel = window.getSelection();
				if (!sel.rangeCount) return;

				const range = sel.getRangeAt(0);
				const startPos = this._getLinePosition(range.startContainer, range.startOffset);
				this.selection.start = this.selection.end = startPos;
			}
		};

		_handleMouseUp = () => {
			if (!this.selection.isSelecting || !this.selection.start) return;
			this.selection.isSelecting = false;

			// 获取最终选区信息
			const sel = window.getSelection();
			if (!sel.rangeCount) return;

			const range = sel.getRangeAt(0);
			this.selection.end = this._getLinePosition(range.endContainer, range.endOffset);
		};

		_handleCopy = (e) => {
			if (this.selection.end) {
				e.preventDefault();
				const copiedText = this._getSelectedText();
				e.clipboardData.setData('text/plain', copiedText);
			}
		};

		_getLinePosition(node, offset) {
			const lineEl = this._findLineElement(node);
			if (!lineEl) return null;

			const line = parseInt(lineEl[INDEX]);
			const textOffset = this._calculateTextOffset(lineEl, node, offset);

			return { line: line, offset: textOffset };
		}

		_restoreSelection = () => {
			if (this.selection.isSelecting) return;
			if (!this.selection.start || !this.selection.end) return;

			const startLineEl = this.content.getValue(this.selection.start.line);
			const endLineEl =  this.content.getValue(this.selection.end.line);

			let start = !startLineEl ? null : this._findTextNodeAndOffset(startLineEl, this.selection.start.offset);
			let end = !endLineEl ? null : this._findTextNodeAndOffset(endLineEl, this.selection.end.offset);

			if (!start) {
				const node = this.content.dom.firstElementChild;
				if (node[INDEX] > this.selection.end.line) return;
				start = {node, offset: 0};
			}
			if (!end) {
				const node = this.content.dom.lastElementChild;
				if (node[INDEX] < this.selection.start.line) return;
				end = {node, offset: 0};
			}

			if (!start || !end) return;

			const range = document.createRange();
			range.setStart(start.node, start.offset);
			range.setEnd(end.node, end.offset);

			const sel = window.getSelection();
			sel.removeAllRanges();
			sel.addRange(range);
		}

		_findLineElement(node) {
			while (node && !node.classList?.contains('line')) {
				node = node.parentElement;
			}
			return node?.closest('.line');
		}

		_calculateTextOffset(lineEl, targetNode, targetOffset) {
			const contentEl = lineEl;
			let offset = 0;
			const walker = document.createTreeWalker(contentEl, NodeFilter.SHOW_TEXT);

			walker.nextNode(); // 跳过行号 第一个文本元素
			while (walker.nextNode()) {
				const currentNode = walker.currentNode;
				if (currentNode === targetNode) {
					return offset + Math.min(targetOffset, currentNode.length);
				}
				offset += currentNode.length;
			}
			return offset;
		}

		_findTextNodeAndOffset(lineEl, targetOffset) {
			let offset = 0;
			const walker = document.createTreeWalker(lineEl, NodeFilter.SHOW_TEXT);

			walker.nextNode(); // 跳过行号文本节点
			while (walker.nextNode()) {
				const node = walker.currentNode;
				const len = node.length;
				if (offset + len >= targetOffset) {
					return { node: node, offset: targetOffset - offset };
				}
				offset += len;
			}

			const lastNode = walker.currentNode;
			return lastNode ? { node: lastNode, offset: lastNode.length } : null;
		}

		_getSelectedText() {
			const { start, end } = this.selection;
			const startLine = start.line;
			const endLine = end.line;
			const startOffset = start.offset;
			const endOffset = end.offset;

			const [firstLine, lastLine] = [Math.min(startLine, endLine), Math.max(startLine, endLine)];
			const isReverse = startLine > endLine;

			let result = '';
			for (let lineNum = firstLine; lineNum <= lastLine; lineNum++) {
				const lineContent = this.lines[lineNum];

				if (lineNum === firstLine && lineNum === lastLine) {
					const start = isReverse ? endOffset : startOffset;
					const end = isReverse ? startOffset : endOffset;
					result += lineContent.slice(start, end);
				} else if (lineNum === firstLine) {
					result += isReverse
						? lineContent.slice(0, endOffset)
						: lineContent.slice(startOffset);
				} else if (lineNum === lastLine) {
					result += isReverse
						? lineContent.slice(startOffset)
						: lineContent.slice(0, endOffset);
				} else {
					result += lineContent;
				}

				if (lineNum !== lastLine) result += '\n';
			}
			return result;
		}

		destroy() {
			// 清理事件监听
			//this.content._wrapper.removeEventListener('mousedown', this._handleMouseDown);
			document.removeEventListener('mouseup', this._handleMouseUp);
			document.removeEventListener('copy', this._handleCopy);
		}
		//endregion

		_posToGlobalOffset(pos) {
			return this.lineOffset[pos.line] + pos.offset;
		}

		getSelectedDiffs() {
			if (!this.selection.start) return [];

			// 获取选区对应的全局偏移量
			const start = this._posToGlobalOffset(this.selection.start);
			const end = this._posToGlobalOffset(this.selection.end);

			const diffList = [];
			for (let i = this.selection.start.line; i <= this.selection.end.line; i++) {
				for (const d of this.lineDiffs[i]) {
					const range = this.isOriginal ? d.originalRange : d.modifiedRange;
					// 只要相交就返回
					if (d.type !== "equal" && range[1] >= start && range[0] < end) {
						diffList.push(d);
					}
				}
			}

			return diffList;
		}

		/**
		 * 编辑器更新，将start至end的内容替换为content并同步更新UI
		 * @param start
		 * @param end
		 * @param content
		 */
		setContent(start, end, content) {
			let text = this._text;
			text = text.substring(0, start)+content+text.substring(end);
			this._text = text;

			let inside = null;
			for (let i = 0; i < this._diffList.length; i++) {
				const diff = this._diffList[i];

				const range = this.isOriginal ? diff.originalRange : diff.modifiedRange;
				// 整体在前
				if (range[1] < start) continue;
				// 整体在后
				else if (range[0] >= end) {
					range[0] += start - end + content.length;
					range[1] += start - end + content.length;
				}
				// 开头在区间外，结尾在区间内
				else if (range[0] < start && range[1] <= end) {
					range[1] = start;
				}
				// 开头在区间内，结尾在区间外
				else if (range[0] >= start && range[1] > end) {
					range[0] = start + content.length;
					range[1] += start - end + content.length;
				}
				// 整体在区间内
				else {
					if (inside === null) {
						diff.type = "equal";
						const newStart = start;
						const newEnd = start + content.length;
						range[0] = newStart;
						range[1] = newEnd;
						inside = diff;
					} else {
						this._diffList.splice(i--, 1);

						let myRange = this.isOriginal ? inside.originalRange : inside.modifiedRange;
						myRange[1] += range[1] - range[0];
					}
				}
			}
		}

		repaint() {
			this._mapDiffToLine(this._text, this._diffList);
			this.content.items = this.lineDiffs.map(x => {return {}});
			this.content.repaint();
		}
	}

	// 初始化比较视图
	const leftView = new SidedViewer(container, original, diffList, true, isUnaligned);
	const rightView = new SidedViewer(container, modified, diffList, false, isUnaligned);

	return {
		mergeToLeft: () => {
			for (const diff of rightView.getSelectedDiffs()) {
				leftView.setContent(diff.originalRange[0], diff.originalRange[1], rightView._text.slice(...diff.modifiedRange));
			}
			leftView.repaint();
			rightView.repaint();
		},
		mergeToRight: () => {
			for (const diff of leftView.getSelectedDiffs()) {
				rightView.setContent(diff.modifiedRange[0], diff.modifiedRange[1], leftView._text.slice(...diff.originalRange));
			}
			leftView.repaint();
			rightView.repaint();
		},
		leftView, rightView
	};
}

export {highlightSideBySide}