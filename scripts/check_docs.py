"""检查独立附属文档链接。"""

from pathlib import Path
import re
import sys


def main() -> int:
    # 检查源文档，不把构建依赖或生成资源当作文档来源。
    root = Path(__file__).resolve().parents[1]
    pages = [root / "README.md", *sorted((root / "docs/wiki").glob("*.md"))]
    failures = []
    local_links = 0
    for page in pages:
        for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", page.read_text(encoding="utf-8")):
            if target.startswith(("https://", "http://", "#")):
                continue
            relative_path = target.split("#", 1)[0]
            local_links += 1
            if not (page.parent / relative_path).is_file():
                failures.append(f"{page.relative_to(root)}: {target}")
    if failures:
        print("文档链接不存在：\n" + "\n".join(failures), file=sys.stderr)
        return 1
    print(f"检查通过：{len(pages)} 个页面，{local_links} 个本地链接。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
