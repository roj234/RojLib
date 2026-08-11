import {highlightSideBySide} from "./merger/side-by-side.js";
import {highlightUnified} from "./merger/unified.js";
import {mockFoxDog} from "./mock.js";
import {appendChildren} from "unconscious";
import {G} from "unconscious/common/Utils.js";
import {ajax} from "unconscious/common/Request.js";

// 数据准备
let {original, modified, diffList} = mockFoxDog(100);

function init() {
	var sidedViewers = highlightSideBySide(G("#side-by-side"), original, modified, diffList, true);
	var unifiedViewer = highlightUnified(G("#unified"), original, modified, diffList);

	appendChildren(document.body, []);

	console.log(sidedViewers, unifiedViewer);

// 更新容器高度的方法
	function setContainerHeight(height) {
		document.querySelectorAll('.text-box').forEach(box =>
			box.style.height = `${height}px`
		);
		sidedViewers.leftView.content.repaint();
		sidedViewers.rightView.content.repaint();
	}

// 初始化容器高度
	setContainerHeight(600);
}

const callback = (data) => {
	const mapping = ["equal","replace","insert","delete"];

	original = data.original;
	modified = data.modified;
	diffList = data.diffList.map(t => {
		return {
			type: mapping[t.type],
			originalRange: t.ranges.slice(0, 2),
			modifiedRange: t.ranges.slice(2, 4)
		}
	});
	init();
};

ajax('http://localhost:8080/diff/mock', null, callback, init);