#!/usr/bin/env python3
"""Check the documentation rules: frontmatter of at most 20 lines, files under 1,000 lines,
relative links resolve, heading anchors exist. Usage: scripts/check-docs.py [file ...]"""
import glob, os, re, sys

def slug(heading):
    heading = re.sub(r'[`*]', '', heading.strip().lower())
    return re.sub(r'[^\w\- ]', '', heading).replace(' ', '-')

def headings(path):
    found, in_code = set(), False
    for line in open(path):
        if line.startswith('```'):
            in_code = not in_code
        if not in_code and re.match(r'#{1,6} ', line):
            found.add(slug(re.sub(r'^#+ ', '', line)))
    return found

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(root)
    docs = sorted(glob.glob('doc/**/*.md', recursive=True))
    everything = docs + ['README.md', 'AGENTS.md']
    targets = [os.path.normpath(a) for a in sys.argv[1:]] or docs
    anchors = {os.path.normpath(f): headings(f) for f in everything}
    problems = []
    for f in targets:
        text = open(f).read()
        lines = text.splitlines()
        if f.startswith('doc/'):
            if not lines or lines[0] != '---':
                problems.append(f'{f}: no frontmatter')
            else:
                end = next((i for i, l in enumerate(lines[1:], 1) if l == '---'), None)
                if end is None or end > 20:
                    problems.append(f'{f}: frontmatter longer than 20 lines')
        if len(lines) > 1000:
            problems.append(f'{f}: {len(lines)} lines')
        for m in re.finditer(r'\]\(([^)\s]*)\)', text):
            link = m.group(1)
            if link.startswith('http'):
                continue
            path, _, anchor = link.partition('#')
            target = os.path.normpath(os.path.join(os.path.dirname(f), path)) if path else os.path.normpath(f)
            if path and not os.path.exists(target):
                problems.append(f'{f}: broken link {link}')
            elif anchor and target.endswith('.md') and anchor not in anchors.get(target, set()):
                problems.append(f'{f}: missing anchor {link}')
    for p in problems:
        print(p)
    print(f'checked {len(targets)} files, {len(problems)} problems')
    sys.exit(1 if problems else 0)

main()
