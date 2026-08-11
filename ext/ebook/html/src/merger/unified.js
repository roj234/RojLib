import {escapeHTML, HeightConfig} from "./util.js"
import {VirtualList} from "unconscious/common/VirtualList.js";

class UnifiedViewer {
	constructor(container, original, modified, diffList) {
		this.container = container;
		this.original = original;
		this.modified = modified;
		this.diffList = diffList;

		// 转换数据
		this._mapDiffToLine();

		const box = <div class="text-box"></div>;
		container.append(box);

		this.content = new VirtualList({
			element: box,
			data: this.lines,
			itemHeight: HeightConfig.lineHeight,
			height: HeightConfig.containerHeight,
			renderer: this._createLine.bind(this)
		});
	}

	// 将差异映射到行结构
	_mapDiffToLine() {
		let originalLine = 1;
		let modifiedLine = 1;

		function emptyLine() {
			return {
				diffList: [],
				originalLine,
				modifiedLine
			};
		}

		let line = emptyLine();
		this.lines = [line];

		for (const diff of this.diffList) {
			const handleContent = (type, content, isOriginal, newLine) => {
				const lines = content.split('\n');
				for (let i = 0; i < lines.length; i++) {
					if (i > 0 || newLine) this.lines.push(line = {
						diffList: [],
						originalLine: isOriginal ? originalLine + i : null,
						modifiedLine: !isOriginal ? modifiedLine + i : null
					});

					line.diffList.push({
						type,
						content: lines[i],
					});
				}

				return lines.length-1;
			}

			switch (diff.type) {
				case 'replace':
					const origContent = this.original.slice(...diff.originalRange);
					const modContent = this.modified.slice(...diff.modifiedRange);

					if (origContent.includes("\n") || modContent.includes("\n")) {
						// 横跨多行的replace需要前后额外换行以区分
						originalLine += handleContent('delete', origContent, true, true);
						modifiedLine += handleContent('insert', modContent, false, true);
						this.lines.push(line = emptyLine());
					} else {
						// 否则inline
						line.diffList.push({
							type: "replace",
							content: origContent,
							content2: modContent,
						});
					}

					break;

				case 'insert':
					modifiedLine += handleContent('insert', this.modified.slice(...diff.modifiedRange), false, false);
					break;

				case 'delete':
					originalLine += handleContent('delete', this.original.slice(...diff.originalRange), true, false);
					break;

				case 'equal':
					const lines = this.modified.slice(...diff.modifiedRange).split('\n');
					for (let i = 0;;) {
						line.diffList.push({
							type: 'equal',
							content: lines[i],
						});

						if (++i === lines.length) break;

						this.lines.push(line = {
							diffList: [],
							originalLine: ++originalLine,
							modifiedLine: ++modifiedLine
						});
					}
					break;
			}
		}
	}

	// 渲染单行
	_createLine(item, lineNum) {
		let content = '';
		let has_both = 0;
		for (const diff of item.diffList) {
			switch (diff.type) {
				case 'replace':
					content += `<span class="replace">${escapeHTML(diff.content)}</span><sup class="modified">${escapeHTML(diff.content2)}</sup>`;
					has_both = 1;
					break;
				case 'insert':
					content += `<span class="insert">${escapeHTML(diff.content)}</span>`;
					has_both = 1;
					break;
				case 'delete':
					content += `<span class="delete">${escapeHTML(diff.content)}</span>`;
					has_both = 1;
					break;
				default:
					content += escapeHTML(diff.content);
			}
		}
		const lineEl = <div></div>;
		lineEl.className = (lineNum&1) ? 'line' : 'line odd';
		if (has_both) lineEl.classList.add("changed");
		lineEl.innerHTML = `<span class="line-numbers"><span class="line-number original">${item.originalLine ?? ''}</span><span class="line-number modified">${item.modifiedLine ?? ''}</span></span>${content}`;
		return lineEl;
	}
}

/**
 * 联合视图
 * @param container 容器
 * @param original 旧的
 * @param modified 新的
 * @param diffList 差异
 * @returns {UnifiedViewer}
 */
function highlightUnified(container, original, modified, diffList) {
	// 初始化并返回实例
	return new UnifiedViewer(container, original, modified, diffList);
}

export {UnifiedViewer, highlightUnified}