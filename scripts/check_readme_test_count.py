#!/usr/bin/env python3
"""README 里的测试条数必须等于真实跑出来的条数。

    python scripts/check_readme_test_count.py

退出码：0 = 一致 · 1 = 不一致或读不到产物

为什么要有它
------------
README 里同一个数字写在**两处**：发版说明那段写「发版前实测：`mvn -o -B test` →
110 项，0 失败」，下面「测试」一节又写「107 项测试覆盖：……」。
实测 110（Maven 输出、Surefire XML、源码注解数三处一致），而下面那句停在 107
——改 README 时漏改了一处，**两句话在同一页上互相矛盾**。

这类数字没有任何东西守着，所以一定会漂：本工作区里 letterpress 的单测数
漂到过 263（实际 270），mcp-sentinel 漂到过 127（实际 131）。
有门禁的两个（letterpress 的 `verify:testcount`、amlagent 的
`check_readme_test_numbers.py`）就没漂。因果关系很干净。

数据从哪来
----------
`target/surefire-reports/*.xml` —— Maven 跑完写下的**真实产物**，
不是从日志里抓 `Tests run:` 那行（日志格式会变，而且可能抓到中途的汇总）。
**读不到就是失败，不是跳过**：否则把 `./mvnw test` 从流水线里拿掉，
这条检查会安静地不再检查任何东西——那是本工作区反复记的
「查不动 ≠ 通过」。

它刻意不做什么
--------------
* **不改 README。** 不一致就报错并把两边的值都打出来，改不改由人决定。
  一个会自己改文档的检查，等于把"文档错了"这件事也一起静默了。
* **不假装能认出所有写法。** 只抓 `NNN 项测试` 这一种明确句式；
  抓不到就明说"没找到声称"，而不是当成通过。
"""

from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORTS = os.path.join(ROOT, "target", "surefire-reports", "*.xml")
README = os.path.join(ROOT, "README.md")

# README 里声称测试条数的句式。**只在明确的句式上匹配**——宽泛地找数字会误伤
# 版本号、端口号、身份证示例之类，而一条会无缘无故变红的检查只会被关掉。
#
# 「测试」两个字**可省**：实测过这里写成 `→ 110 项，0 失败`（紧跟"项"的是逗号不是
# "测试"），只认 `\d+ 项测试` 会漏掉这一处——**而它恰恰是同一页上另一处声称**，
# 漏掉它这条检查就只看了一半。放宽到 `\d+ 项(?:测试)?` 之后全文仍然只有这两处命中
# （用 `finditer` 逐处核过上下文），没有误伤。
CLAIM = re.compile(r"(\d+)\s*项(?:测试)?")


def actual_count() -> int | None:
    files = glob.glob(REPORTS)
    if not files:
        return None
    total = 0
    for path in files:
        root = ET.parse(path).getroot()
        total += int(root.get("tests", 0))
    return total


def main() -> int:
    print("\nREADME 测试条数对账")
    print("─" * 64)

    actual = actual_count()
    if actual is None:
        print("  ✗ 找不到 %s" % REPORTS)
        print("      这个检查读的是 `./mvnw test` 写下的真实产物。先跑一次测试。")
        print("      它**不会**因为读不到就当作通过——那正是「把检查拿掉就静默失效」。")
        return 1

    print("  · 本次实测 %d 项（来自 %d 个 Surefire XML）"
          % (actual, len(glob.glob(REPORTS))))

    with open(README, encoding="utf-8") as handle:
        text = handle.read()

    claims = [(int(m.group(1)), text[:m.start()].count("\n") + 1)
              for m in CLAIM.finditer(text)]

    if not claims:
        print("  ✗ README 里一处「NNN 项测试」都没找到")
        print("      要么句式改了、要么这句话被删了。**这不等于数字是对的**——")
        print("      检查认不出声称，就什么都守不住。请同步更新本脚本的正则。")
        return 1

    bad = [(n, line) for n, line in claims if n != actual]
    for n, line in claims:
        mark = "✓" if n == actual else "✗"
        print("  %s README:%d 声称 %d 项" % (mark, line, n))

    if bad:
        print("\n  实测 %d 项，但有 %d 处对不上（上面标 ✗ 的）。" % (actual, len(bad)))
        print("  改不改由人决定；这个脚本只负责把两边的值都摆出来。")
        return 1

    print("  ✓ %d 处声称全部与实测一致" % len(claims))
    return 0


if __name__ == "__main__":
    sys.exit(main())
