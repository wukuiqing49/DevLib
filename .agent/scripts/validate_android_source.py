#!/usr/bin/env python3
"""按变更文件检查明显的中文文档、Base 与资源化问题；不替代语义分析和人工审查。"""
import argparse
import json
import re
import subprocess
from pathlib import Path

TOKEN = re.compile(r'/\*[\s\S]*?\*/|//[^\n]*|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
DECL = re.compile(r'\b(?:enum\s+class|class|interface|object|enum)\s+(\w+)|\bfun\s+(?:<[^>]+>\s*)?(\w+)\s*\(')
JAVA_METHOD = re.compile(r'^\s*(?:(?:public|private|protected|static|final|synchronized|abstract|native|default)\s+)*(?:[\w<>\[\],.?]+\s+)?(\w+)\s*\([^;{}]*\)\s*(?:throws\s+[\w., ]+)?[{;]', re.M)


def scan(path, source):
    """检查完整选中文件，保留原行号；复杂签名及间接继承交由人工复核。"""
    tokens = list(TOKEN.finditer(source))
    masked = TOKEN.sub(lambda m: ''.join('\n' if c == '\n' else ' ' for c in m[0]), source)
    findings = []
    def add(rule, offset, symbol, message):
        findings.append(dict(path=path, line=source.count('\n', 0, offset)+1, rule=rule, symbol=symbol, message=message))
    if path.endswith(('.kt', '.java')):
        declarations = [(m.start(), m.group(1) or m.group(2)) for m in DECL.finditer(masked)]
        if path.endswith('.java'):
            declarations += [(m.start(), m[1]) for m in JAVA_METHOD.finditer(masked) if m[1] not in {'if','for','while','switch','catch','return','super','this'}]
        for start, name in declarations:
            docs = [m for m in tokens if m.end() <= start and m[0].startswith('/**')]
            doc = docs[-1] if docs else None
            gap = masked[doc.end():start] if doc else ''
            # Only annotations and declaration modifiers may separate documentation and declaration.
            gap = re.sub(r'@\w+(?:\.\w+)*(?:\([^)]*\))?', '', gap)
            gap = re.sub(r'\b(?:public|private|protected|internal|open|abstract|final|override|suspend|inline|data|sealed|enum|annotation|value|actual|expect|static)\b', '', gap)
            if not doc or gap.strip() or not re.search(r'[\u4e00-\u9fff]', doc[0]):
                add('DOC001', start, name, '类型/方法缺少紧邻的中文 KDoc/JavaDoc')
        for m in re.finditer(r'(?:\bextends\s+|:\s*)(?:(?:androidx?\.\w+\.)*)(AppCompatActivity|ComponentActivity|FragmentActivity|Fragment|ViewModel|RecyclerView\.Adapter|ListAdapter)\b', masked):
            add('BASE001', m.start(), m[1], '直接继承平台组件，请检查项目 Base 复用或登记具体例外')
        for m in re.finditer(r'(?:\.(?:text|hint|contentDescription)\s*=\s*|\b(?:setText|setHint|setContentDescription)\s*\(\s*)"([^"\n]+)"', source):
            if masked[m.start():m.start()+1].strip():
                add('I18N001', m.start(), m[1], '可见文案应引用字符串资源')
    elif path.endswith('.xml'):
        clean = re.sub(r'<!--[\s\S]*?-->', lambda m: ' '*len(m[0]), source)
        for m in re.finditer(r'android:(text|hint|contentDescription)\s*=\s*"([^"\n]+)"', clean):
            if not m[2].startswith(('@','?')):
                add('I18N001', m.start(), m[1], '布局可见文案应引用字符串资源')
    return findings


def apply_exceptions(findings, entries):
    """仅接受按文件、规则和具体行登记且有业务依据的例外。"""
    for e in entries:
        if not all(e.get(k) for k in ('path','rule','line','reason','alternativeValidation','reviewAfter')) or any(c in e['path'] for c in '*?'):
            raise ValueError('Exception requires exact path/rule/line, reason, alternativeValidation and reviewAfter')
    for f in findings:
        f['excepted'] = any(all(f[k] == e[k] for k in ('path','rule','line')) for e in entries)
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-root', default='.')
    parser.add_argument('--file', action='append', help='Explicit task file; repeatable. Checks whole selected file.')
    parser.add_argument('--base-ref', default='HEAD', help='Default selection: changed/untracked app/feature main source files.')
    parser.add_argument('--exceptions', help='JSON array of exact finding exceptions with reason/alternativeValidation/reviewAfter.')
    parser.add_argument('--report', help='Write JSON audit evidence.')
    args = parser.parse_args()
    root = Path(args.project_root).resolve()
    files = args.file
    if files is None:
        def git(*argv):
            return subprocess.check_output(['git', '-C', str(root), *argv], text=True, encoding='utf-8').split('\0')
        files = git('diff','--name-only','-z',args.base_ref,'--') + git('ls-files','--others','--exclude-standard','-z')
        files = [f for f in files if f.startswith(('app/','feature/')) and '/src/main/' in f]
    findings = []
    scanned = []
    for raw in sorted(set(files)):
        file = (root / raw).resolve()
        relative = file.relative_to(root).as_posix()
        if file.suffix not in ('.kt','.java','.xml') or not file.is_file():
            continue
        scanned.append(relative)
        findings.extend(scan(relative, file.read_text(encoding='utf-8')))
    entries = json.loads((root / args.exceptions).read_text(encoding='utf-8')) if args.exceptions else []
    apply_exceptions(findings, entries)
    result = dict(scope='whole selected files; not changed-line or compiler analysis', files=scanned, findings=findings)
    if args.report:
        report = root / args.report
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    for f in findings:
        print(f"{f['path']}:{f['line']} {f['rule']} {'EXCEPTION' if f['excepted'] else 'ERROR'} {f['symbol']}: {f['message']}")
    errors = sum(not f['excepted'] for f in findings)
    print(f'Source audit: {len(scanned)} files, {errors} findings; manual semantic/adaptation review still required.')
    return 1 if errors else 0

if __name__ == '__main__':
    raise SystemExit(main())
