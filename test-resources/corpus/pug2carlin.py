#!/usr/bin/env python3
"""pug -> carlin corpus converter.

Mechanical morphing of the pugjs golden-file corpus to carlin syntax:
  - attribute parens -> Clojure literal maps (with JS->Clojure value translation)
  - `- var x = e` runs -> `- (let [x e ...])` with structural re-indentation
  - each/for index forms, if/unless/case/when condition translation
  - mixin decls (binding vectors) and calls (+name form...)
  - extends/include ref extension rename, `extend` alias -> extends
  - `key!=v` attrs -> (raw v); buffered !=/= expression translation
  - interpolation #{js} -> #{clj}; dot-block & filter bodies preserved verbatim
Anything untranslatable is flagged CARLIN-TODO and reported.
"""
import re, sys, os, json

FLAGS = []  # (file, line_no, reason, text)
CUR_FILE = ""

def flag(reason, text, lineno=0):
    FLAGS.append((CUR_FILE, lineno, reason, text))
    return f"__CARLIN_TODO__[{reason}]({text})"

# ---------------- JS expression -> Clojure form ----------------

TOKEN_RE = re.compile(r"""
    (?P<ws>\s+)
  | (?P<str>'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*")
  | (?P<num>\d+\.\d+|\d+)
  | (?P<id>[$A-Za-z_][$\w]*)
  | (?P<op>===|!==|==|!=|<=|>=|&&|\|\||[-+*/%<>!?:.,()\[\]{}=])
""", re.VERBOSE)

def tokenize(s):
    toks, i = [], 0
    while i < len(s):
        m = TOKEN_RE.match(s, i)
        if not m: raise ValueError(f"tok@{i}:{s[i:i+10]!r}")
        i = m.end()
        if m.lastgroup != 'ws':
            toks.append((m.lastgroup, m.group()))
    return toks

def clj_str(js_quoted):
    body = js_quoted[1:-1]
    # unescape JS source escapes to the runtime string
    body = re.sub(r"\\(.)", lambda m: {'n':'\n','t':'\t'}.get(m.group(1), m.group(1)), body)
    # re-escape for a Clojure string literal
    body = body.replace('\\', '\\\\').replace('"', '\\"')
    return '"' + body + '"'

class P:
    def __init__(self, toks): self.t, self.i = toks, 0
    def peek(self, k=0): return self.t[self.i+k] if self.i+k < len(self.t) else (None, None)
    def next(self): tok = self.t[self.i]; self.i += 1; return tok
    def at(self, v): return self.peek()[1] == v
    def eat(self, v):
        if not self.at(v): raise ValueError(f"expected {v} got {self.peek()}")
        return self.next()

def parse_expr(p):  # ternary (lowest)
    c = parse_or(p)
    if p.at('?'):
        p.next(); a = parse_expr(p); p.eat(':'); b = parse_expr(p)
        return f"(if {c} {a} {b})"
    return c

def _binop(sub, ops, mk):
    def go(p):
        left = sub(p)
        while p.peek()[1] in ops:
            op = p.next()[1]; right = sub(p)
            left = mk(op, left, right)
        return left
    return go

def is_strlit(x): return isinstance(x, str) and x.startswith('"')

def mk_add(op, l, r):
    if op == '+':
        if is_strlit(l) or is_strlit(r) or (l.startswith('(str ') ):
            if l.startswith('(str '):
                return l[:-1] + ' ' + r + ')'
            return f"(str {l} {r})"
        return f"(+ {l} {r})"
    return f"(- {l} {r})"

CMP = {'==':'=', '===':'=', '!=':'not=', '!==':'not=', '<':'<', '>':'>', '<=':'<=', '>=':'>='}

parse_mul  = None  # fwd
def parse_unary(p):
    if p.at('!'):
        p.next(); return f"(not {parse_unary(p)})"
    if p.at('-'):
        p.next(); return f"(- {parse_unary(p)})"
    return parse_postfix(p)

def parse_primary(p):
    kind, val = p.peek()
    if val == '(':
        p.next(); e = parse_expr(p); p.eat(')'); return e
    if kind == 'str': p.next(); return clj_str(val)
    if kind == 'num': p.next(); return val
    if val == '[':
        p.next(); items = []
        while not p.at(']'):
            items.append(parse_expr(p))
            if p.at(','): p.next()
        p.eat(']'); return '[' + ' '.join(items) + ']'
    if val == '{':
        p.next(); pairs = []
        while not p.at('}'):
            k = p.next()[1]
            if k.startswith(("'", '"')): k = clj_str(k); key = k  # string key stays string
            else: key = ':' + k
            p.eat(':'); v = parse_expr(p)
            pairs.append(f"{key} {v}")
            if p.at(','): p.next()
        p.eat('}'); return '{' + ' '.join(pairs) + '}'
    if kind == 'id':
        p.next()
        if val == 'true': return 'true'
        if val == 'false': return 'false'
        if val in ('null', 'undefined'): return 'nil'
        return val
    raise ValueError(f"primary? {p.peek()}")

def parse_postfix(p):
    e = parse_primary(p)
    while True:
        if p.at('.'):
            p.next(); name = p.next()[1]
            if p.at('('):  # method call -> unsupported
                raise ValueError(f"method call .{name}(")
            e = f"(:{name} {e})"
        elif p.at('['):
            p.next(); idx = parse_expr(p); p.eat(']')
            e = f"(get {e} {idx})"
        elif p.at('(') and re.match(r'^[$\w.-]+$', e):  # fn call on bare symbol
            p.next(); args = []
            while not p.at(')'):
                args.append(parse_expr(p))
                if p.at(','): p.next()
            p.eat(')')
            e = f"({e}{(' ' + ' '.join(args)) if args else ''})"
        else:
            return e

parse_mul = _binop(parse_unary, {'*','/','%'}, lambda o,l,r: f"({'mod' if o=='%' else o} {l} {r})")
parse_add = _binop(parse_mul, {'+','-'}, mk_add)
parse_cmp = _binop(parse_add, set(CMP), lambda o,l,r: f"({CMP[o]} {l} {r})")
parse_and = _binop(parse_cmp, {'&&'}, lambda o,l,r: f"(and {l} {r})")
parse_or  = _binop(parse_and, {'||'}, lambda o,l,r: f"(or {l} {r})")

def js2clj(s, lineno=0):
    s = s.strip()
    if not s: return s
    try:
        p = P(tokenize(s))
        out = parse_expr(p)
        if p.i != len(p.t):
            raise ValueError(f"trailing tokens {p.t[p.i:]}")
        return out
    except ValueError as e:
        return flag(str(e), s, lineno)

# ---------------- interpolation in text ----------------

def convert_text(s, lineno=0):
    out, i = [], 0
    while i < len(s):
        t = s.find('#[', i)
        j = s.find('#{', i)
        k = s.find('!{', i)
        if t != -1 and (j == -1 or t < j) and (k == -1 or t < k) and (t == 0 or s[t-1] != '\\'):
            out.append(s[i:t])
            depth, m, q = 1, t+2, None
            while m < len(s) and depth:
                ch = s[m]
                if q:
                    if ch == q and s[m-1] != '\\': q = None
                elif ch in '\'"': q = ch
                elif ch == '[': depth += 1
                elif ch == ']': depth -= 1
                m += 1
            inner = s[t+2:m-1]
            out.append('#[' + convert_line(inner, lineno).strip() + ']')
            i = m
            continue
        if j == -1: j = len(s)+1
        if k == -1: k = len(s)+1
        n = min(j, k)
        if n > len(s):
            out.append(s[i:]); break
        if n > 0 and s[n-1] == '\\':  # escaped
            out.append(s[i:n+2]); i = n+2; continue
        out.append(s[i:n])
        depth, m = 1, n+2
        while m < len(s) and depth:
            if s[m] == '{': depth += 1
            elif s[m] == '}': depth -= 1
            m += 1
        expr = s[n+2:m-1]
        out.append(s[n:n+2] + js2clj(expr, lineno) + '}')
        i = m
    return ''.join(out)

# ---------------- attribute list -> Clojure map ----------------

def split_attrs(s):
    """split pug attr list on top-level commas AND spaces"""
    parts, buf, depth, q = [], '', 0, None
    for ch in s:
        if q:
            buf += ch
            if ch == q and not buf.endswith('\\' + q): q = None
            continue
        if ch in '\'"': q = ch; buf += ch; continue
        if ch in '([{': depth += 1
        if ch in ')]}': depth -= 1
        if depth == 0 and ch in ', \t\n':
            if buf: parts.append(buf); buf = ''
            continue
        buf += ch
    if buf: parts.append(buf)
    return parts

ATTR_RE = re.compile(r"^(?P<name>[^\s=!]+)(?P<op>!?=)?(?P<val>.*)$", re.S)

OPS_END = tuple('= != ? : + - * / % && || == === !== < > <= >='.split())
OP_PARTS = {'?', ':', '+', '-', '*', '/', '%', '&&', '||', '==', '===', '!=', '!==', '<', '>', '<=', '>='}
def merge_attr_parts(parts):
    out = []
    for p in parts:
        if out and (out[-1].endswith(OPS_END) or p in OP_PARTS):
            out[-1] += ' ' + p
        else:
            out.append(p)
    return out

def convert_attrs(inner, lineno=0):
    pairs = []
    for part in merge_attr_parts(split_attrs(inner)):
        m = ATTR_RE.match(part)
        if not m:
            pairs.append(flag('attr-unparsed', part, lineno)); continue
        name, op, val = m.group('name'), m.group('op'), m.group('val')
        if name.startswith(("'", '"')):
            key = clj_str(name)
        elif re.match(r'^[A-Za-z_][\w-]*$', name):
            key = ':' + name
        else:
            key = '"' + name + '"'  
        if op is None:
            pairs.append(f"{key} true")
        else:
            # attr string interpolation "/#{x}" -> (str ...)
            if re.match(r'^([\'"]).*#\{.*\1$', val):
                parts, segs = [], re.split(r'#\{([^}]*)\}', val[1:-1])
                for idx, seg in enumerate(segs):
                    if idx % 2 == 0:
                        if seg: parts.append(clj_str("'" + seg + "'"))
                    else:
                        parts.append(js2clj(seg, lineno))
                v = f"(str {' '.join(parts)})"
            else:
                v = js2clj(val, lineno)
            if op == '!=':
                v = f"(raw {v})"
            pairs.append(f"{key} {v}")
    return '{' + ' '.join(pairs) + '}'

# ---------------- line-level machinery ----------------

def find_paren_span(s, start):
    depth, i, q = 0, start, None
    while i < len(s):
        ch = s[i]
        if q:
            if ch == q and s[i-1] != '\\': q = None
        elif ch in '\'"': q = ch
        elif ch == '(': depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0: return i
        i += 1
    return -1

TAG_HEAD = re.compile(r'^(?P<head>[\w.#\-:$|]*?)\(')

def convert_tag_line(line, lineno):
    """convert `tag(attrs)tail` incl. shorthand reordering; returns converted line"""
    m = TAG_HEAD.match(line.strip())
    indent = line[:len(line)-len(line.lstrip())]
    s = line.strip()
    p_open = s.find('(')
    head = s[:p_open]
    p_close = find_paren_span(s, p_open)
    inner = s[p_open+1:p_close]
    tail = s[p_close+1:]
    # trailing shorthand after parens: `.button`, `#id` -> move before map
    m2 = re.match(r'^(?P<short>(?:[.#][\w-]+)+)(?P<rest>.*)$', tail)
    if m2:
        head += m2.group('short'); tail = m2.group('rest')
    attrs = convert_attrs(inner, lineno)
    # &attributes(expr)
    m3 = re.match(r'^&attributes\((.*)\)(.*)$', tail)
    amp = ''
    if m3:
        amp = f"&attributes {js2clj(m3.group(1), lineno)}"
        tail = m3.group(2)
    return indent + head + attrs + amp + convert_tail(tail, lineno)

def convert_tail(tail, lineno):
    """post-attr tail: `= expr`, `!= expr`, ` text`, `.`, `/`, `: sub`"""
    if tail.startswith('&attributes('):
        close = find_paren_span(tail, len('&attributes'))
        if close != -1:
            return '&attributes ' + js2clj(tail[12:close], lineno) + convert_tail(tail[close+1:], lineno)
    if tail.startswith('!='):
        return '!= ' + js2clj(tail[2:], lineno)
    if tail.startswith('='):
        return '= ' + js2clj(tail[1:], lineno)
    if tail.startswith(': '):
        return ': ' + convert_line(tail[2:], lineno)
    if tail in ('.', '/', ''):
        return tail
    return convert_text(tail, lineno)

EACH_RE = re.compile(r'^(each|for)\s+(?P<b1>[$\w]+)(\s*,\s*(?P<b2>[$\w]+))?\s+in\s+(?P<coll>.+)$')
VAR_RE  = re.compile(r'^-\s*var\s+(?P<name>[$\w]+)\s*=\s*(?P<expr>.+?);?\s*$')
MIXIN_DEF = re.compile(r'^mixin\s+(?P<name>[\w-]+)\s*(\((?P<args>[^)]*)\))?\s*$')
MIXIN_CALL = re.compile(r'^\+(?P<name>[\w-]+)(?P<short>(?:[.#][\w-]+)+)?(\((?P<args>.*)\))?(?P<tail>.*)$')

def convert_line(s, lineno):
    """convert a single logical line's stripped content (no dot-block bodies here)"""
    st = s.strip()
    indent = s[:len(s)-len(s.lstrip())]
    if not st: return s
    # keep literal html lines & pipes-with-text (interpolation converted)
    if st.startswith('<'): return indent + convert_text(st, lineno)
    if st.startswith('| '): return indent + '| ' + convert_text(st[2:], lineno)
    if st == '|': return s
    if st.startswith('//'): return s  # comments verbatim
    if st.startswith('extends ') or st.startswith('extend ') or st.startswith('include'):
        st = re.sub(r'^extend\s', 'extends ', st)
        st = st.replace('.pug', '.carlin').replace('.jade', '.carlin')
        return indent + st
    if st.startswith('doctype') or st == 'block' or st.startswith('block ') or st.startswith('append ') or st.startswith('prepend '):
        return indent + re.sub(r'^(append|prepend)\s', r'block \1 ', st)
    m = MIXIN_DEF.match(st)
    if m:
        args = m.group('args') or ''
        binds = []
        for a in [x.strip() for x in args.split(',') if x.strip()]:
            if a.startswith('...'): binds.append('& ' + a[3:])
            else: binds.append(a)
        return indent + f"mixin {m.group('name')} [{' '.join(binds)}]"
    m = re.match(r'^\+(?P<name>[\w-]+)(?P<rest>.*)$', st)
    if m and not st.startswith('+='):
        rest, shorts, attr_parts, amp, args, tail = m.group('rest'), '', [], '', [], ''
        while rest:
            if rest.startswith('&attributes('):
                close = find_paren_span(rest, len('&attributes'))
                if close == -1:
                    tail = rest; break
                amp = '&attributes ' + js2clj(rest[len('&attributes(')+0+1-1:close] if False else rest[12:close], lineno)
                rest = rest[close+1:]
            elif rest[0] == '(':
                close = find_paren_span(rest, 0)
                if close == -1:
                    tail = rest; break
                inner = rest[1:close]
                parts = split_attrs(inner)
                if any(re.match(r'^("[^"]*"|\'[^\']*\'|[^\s=!()]+)!?=', p) for p in parts):
                    attr_parts.append(inner)
                else:
                    args += [js2clj(a, lineno) for a in parts if a.strip()]
                rest = rest[close+1:]
            elif rest[0] in '.#':
                m2 = re.match(r'^((?:[.#][\w-]+)+)(.*)$', rest, re.S)
                if not m2:
                    tail = rest; break
                shorts += m2.group(1); rest = m2.group(2)
            else:
                tail = rest; break
        out = ('+(' + m.group('name') + ' ' + ' '.join(args) + ')') if args else ('+' + m.group('name'))
        out += shorts
        if attr_parts: out += convert_attrs(' '.join(attr_parts), lineno)
        if amp: out += amp
        if tail.strip(): out += convert_tail(tail if tail[0] in '=!:./' else ' ' + convert_text(tail.strip(), lineno), lineno) if tail[0] in '=!:./' else ' ' + convert_text(tail.strip(), lineno)
        return indent + out
    for kw in ('if ', 'else if ', 'unless ', 'while ', 'case '):
        if st.startswith(kw):
            return indent + kw + js2clj(st[len(kw):], lineno)
    if st.startswith('when '):
        rest = st[5:]
        # block expansion `when v: p x`
        depth, q, cut = 0, None, -1
        for ci, ch in enumerate(rest):
            if q:
                if ch == q and rest[ci-1] != '\\': q = None
            elif ch in '\'"': q = ch
            elif ch in '([{': depth += 1
            elif ch in ')]}': depth -= 1
            elif ch == ':' and depth == 0 and ci+1 < len(rest) and rest[ci+1] in ' \t':
                cut = ci; break
        if cut >= 0:
            return indent + 'when ' + js2clj(rest[:cut], lineno) + ': ' + convert_line(rest[cut+1:].strip(), lineno)
        return indent + 'when ' + js2clj(rest, lineno)
    if st == 'default' or st.startswith('default:'):
        if st.startswith('default:'):
            return indent + 'default: ' + convert_line(st[8:].strip(), lineno)
        return s
    m = EACH_RE.match(st)
    if m:
        b1, b2, coll = m.group('b1'), m.group('b2'), js2clj(m.group('coll'), lineno)
        if b2:  # pug: val, index -> [i val] map-indexed
            return indent + f"each [{b2} {b1}] in (map-indexed vector {coll})"
        return indent + f"each {b1} in {coll}"
    if st.startswith('- '):  # non-var unbuffered code
        return indent + '- ' + js2clj(st[2:], lineno)
    if st.startswith('!='):
        return indent + '!= ' + js2clj(st[2:], lineno)
    if st.startswith('='):
        return indent + '= ' + js2clj(st[1:], lineno)
    # tag with attrs?
    if TAG_HEAD.match(st):
        return convert_tag_line(s, lineno)
    # tag with tail (text/=/./: )
    m = re.match(r'^(?P<tag>[\w.#\-:$]+)(?P<tail>[=!:. /&].*|\s.*)?$', st)
    if m and m.group('tail'):
        tail = m.group('tail')
        if tail.startswith(' '):
            return indent + m.group('tag') + ' ' + convert_text(tail[1:], lineno)
        return indent + m.group('tag') + convert_tail(tail, lineno)
    return indent + convert_text(st, lineno)

def indent_of(line):
    return len(line) - len(line.lstrip()) if line.strip() else None

DOT_BLOCK = re.compile(r'^[^/|<].*\.$')  # tag line ending in '.'
FILTER_LINE = re.compile(r'^\s*:[\w-]+')

def join_multiline_parens(lines):
    """join lines while inside unbalanced attr parens / mixin call parens"""
    out, buf = [], None
    for ln in lines:
        cur = (buf + ' ' + ln.strip()) if buf is not None else ln
        # count depth outside quotes
        depth, q = 0, None
        for i, ch in enumerate(cur):
            if q:
                if ch == q and cur[i-1] != '\\': q = None
            elif ch in '\'"': q = ch
            elif ch == '(': depth += 1
            elif ch == ')': depth -= 1
        if depth > 0:
            buf = cur
        else:
            out.append(cur); buf = None
    if buf is not None: out.append(buf)
    return out

def convert_block(lines, start, base_indent, out, lineno0):
    """recursive: handles `- var` runs by nesting a let and re-indenting."""
    i = start
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            out.append(''); i += 1; continue
        ind = indent_of(line)
        if ind < base_indent:
            return i
        m = VAR_RE.match(line.strip())
        if m:
            binds = [(m.group('name'), js2clj(m.group('expr'), lineno0+i))]
            j = i + 1
            while j < len(lines):
                if not lines[j].strip(): j += 1; continue
                if indent_of(lines[j]) != ind: break
                m2 = VAR_RE.match(lines[j].strip())
                if not m2: break
                binds.append((m2.group('name'), js2clj(m2.group('expr'), lineno0+j)))
                j += 1
            pad = ' ' * ind
            bindstr = ' '.join(f"{n} {e}" for n, e in binds)
            out.append(f"{pad}- (let [{bindstr}])")
            # everything following at >= ind becomes children: +2 indent
            sub = []
            k = j
            while k < len(lines):
                if lines[k].strip() and indent_of(lines[k]) < ind: break
                sub.append(('  ' + lines[k]) if lines[k].strip() else '')
                k += 1
            convert_block(sub, 0, ind + 2, out, lineno0 + j)
            return k if k >= len(lines) else convert_block(lines, k, base_indent, out, lineno0)
        # dot block / filter: convert head, copy body verbatim
        st = line.strip()
        converted = convert_line(line, lineno0+i)
        out.append(converted)
        is_dot = converted.rstrip().endswith('.') and DOT_BLOCK.match(converted.strip())
        is_filter = FILTER_LINE.match(line)
        i += 1
        if is_dot or is_filter:
            while i < len(lines):
                if lines[i].strip() and indent_of(lines[i]) <= ind: break
                # dot blocks interpolate (pug & carlin); filter bodies are fully verbatim
                out.append(convert_text(lines[i], lineno0+i) if is_dot else lines[i]); i += 1
    return i

def convert_file(text):
    lines = join_multiline_parens(text.split('\n'))
    out = []
    convert_block(lines, 0, 0, out, 1)
    return '\n'.join(out)

def main(src_root, dst_root):
    global CUR_FILE
    for dirpath, _, files in os.walk(src_root):
        for f in files:
            src = os.path.join(dirpath, f)
            rel = os.path.relpath(src, src_root)
            if f.endswith(('.pug', '.jade')):
                dst = os.path.join(dst_root, re.sub(r'\.(pug|jade)$', '.carlin', rel))
                CUR_FILE = rel
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(src, encoding='utf-8-sig') as fh: text = fh.read()
                with open(dst, 'w') as fh: fh.write(convert_file(text))
            elif f.endswith('.html'):
                dst = os.path.join(dst_root, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(src, 'rb') as a, open(dst, 'wb') as b: b.write(a.read())
    with open(os.path.join(dst_root, 'TODO-flags.json'), 'w') as fh:
        json.dump([{'file': f, 'line': l, 'reason': r, 'text': t} for f, l, r, t in FLAGS], fh, indent=1)
    print(f"flags: {len(FLAGS)} in {len(set(f for f,_,_,_ in FLAGS))} files")

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
