import argparse
import collections
import json
import os
from pathlib import Path
import random
import sqlite3
import subprocess
import tempfile


parser = argparse.ArgumentParser(description='Check SQL rewrite coverage and differential results on a fixed corpus.')
parser.add_argument('--suite', choices=['working', 'holdout'], default='working')
parser.add_argument('--jar', type=Path, default=Path(__file__).resolve().parent.parent / 'target/sql-demo.jar')
args = parser.parse_args()
root = Path(__file__).resolve().parent
cases = json.loads((root / f'{args.suite}.json').read_text())
java_bin = Path(os.environ['JAVA_HOME']) / 'bin'

# This witness detects a SQLite 3.53.0 OFFSET regression independently of our rewriter.
with sqlite3.connect(':memory:') as db:
    db.executescript("CREATE TABLE customer(id INTEGER PRIMARY KEY, name TEXT);"
                     "CREATE TABLE orders(id INTEGER PRIMARY KEY, customer_id INTEGER);"
                     "INSERT INTO customer VALUES (1,'Bob'),(2,NULL),(3,'Ada'),(4,'Ada');"
                     "INSERT INTO orders VALUES (1,2),(2,2),(3,3),(4,4);")
    actual = db.execute('SELECT c.id, c.name FROM customer c WHERE EXISTS '
                        '(SELECT 1 FROM orders o WHERE o.customer_id=c.id) '
                        'ORDER BY c.id LIMIT 2 OFFSET 1').fetchall()
    if actual != [(3, 'Ada'), (4, 'Ada')]:
        raise SystemExit(f'SQLite {sqlite3.sqlite_version} fails the independent engine witness: {actual}')
db.close()

with tempfile.TemporaryDirectory(prefix='sql-rewrite-evaluation-') as classes:
    subprocess.run([str(java_bin/'javac'), '-cp', str(args.jar), '-d', classes,
                    str(root/'RewriteProbe.java')], check=True)
    probe = subprocess.run([str(java_bin/'java'), '-Xmx512m', '-cp', f'{classes}{os.pathsep}{args.jar}',
                            'RewriteProbe', str(root/f'{args.suite}.json')],
                           check=True, capture_output=True, text=True)
results = {item['id']: item for item in json.loads(probe.stdout)}
failures, misses = [], []
comparisons = 0
seed_start = 0 if args.suite == 'working' else 100
for case in cases:
    result = results[case['id']]
    if 'error' in result:
        failures.append([case['id'], 'analyzer', result['error']])
        continue
    sql = result['sql']
    if not result['stable']:
        failures.append([case['id'], 'unstable-tree-or-rewrite'])
    if any(word not in sql for word in case['required']) or any(word in sql for word in case['forbidden']):
        misses.append(case['id'])
    for forbidden in ('SELECT *', 'public.', ' AS t0', ' AS t1'):
        if forbidden in sql and forbidden not in case['sql']:
            failures.append([case['id'], 'readability', forbidden])
    for seed in range(seed_start, seed_start + 32):
        rng = random.Random(seed)
        customers = [(i, rng.choice([None, 'Ada', 'Ada', 'Bob', '']))
                     for i in range(1, rng.randrange(0, 16)+1)]
        orders = [(i, rng.choice([None, 0, *range(1, 16)]), rng.choice([None, 'paid', 'paid', 'new', '']))
                  for i in range(1, rng.randrange(0, 45)+1)]
        with sqlite3.connect(':memory:') as db:
            db.executescript('CREATE TABLE customer(id INTEGER PRIMARY KEY, name TEXT);'
                             'CREATE TABLE orders(id INTEGER PRIMARY KEY, customer_id INTEGER, status TEXT);')
            db.executemany('INSERT INTO customer VALUES (?, ?)', customers)
            db.executemany('INSERT INTO orders VALUES (?, ?, ?)', orders)
            try:
                before = db.execute(case['sql'])
                labels = [c[0] for c in before.description]
                before_rows = before.fetchall()
                after = db.execute(sql)
                after_labels = [c[0] for c in after.description]
                after_rows = after.fetchall()
                comparisons += 1
                if labels != after_labels:
                    failures.append([case['id'], seed, 'column-names', labels, after_labels])
                compare = list if case['ordered'] else collections.Counter
                if compare(before_rows) != compare(after_rows):
                    failures.append([case['id'], seed, 'rows', before_rows, after_rows])
            except sqlite3.Error as error:
                failures.append([case['id'], seed, 'execution', str(error)])
        db.close()
opportunities = 18 if args.suite == 'working' else 7
covered = opportunities - len(misses)
passed = not failures and (covered >= 15 if args.suite == 'working' else not misses)
print(json.dumps(dict(suite=args.suite, sqlite=sqlite3.sqlite_version, queries=len(cases), datasets=32,
                      comparisons=comparisons, opportunities=opportunities, covered=covered,
                      missed=misses, violations=failures, passed=passed), ensure_ascii=False, indent=2))
raise SystemExit(0 if passed else 1)
