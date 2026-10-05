#!/usr/bin/env python3
"""把 Maven 失败测试摘要写入 CI 注解，便于从检查结果定位失败。"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def annotation(message):
    # CI 基线只使用合成输入；仍遮蔽常见凭据赋值，避免诊断输出带出秘密。
    message = re.sub(r'(?i)(api[-_]?key|token|password|secret)\s*[=:]\s*[^\s,;]+',
                     r'\1=[REDACTED]', message)
    message = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::error::' + message[:6000])


def main():
    root = Path(__file__).resolve().parents[1]
    failures = 0
    for report in sorted(root.glob('*/target/surefire-reports/TEST-*.xml')):
        try:
            suite = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        for case in suite.iter('testcase'):
            for tag in ('failure', 'error'):
                result = case.find(tag)
                if result is not None:
                    identity = case.get('classname', '') + '.' + case.get('name', '')
                    detail = result.get('message', '') + '\n' + (result.text or '')
                    annotation(identity + ': ' + detail[:4000])
                    failures += 1
    if not failures:
        log = root / 'target/ci-verify.log'
        if log.exists():
            lines = [line for line in log.read_text(errors='replace').splitlines()
                     if '[ERROR]' in line]
            annotation('Maven verification failed before a test report: ' + '\n'.join(lines[-12:]))


if __name__ == '__main__':
    main()
