(ns simpleviz.highlight-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.highlight :refer [highlight]]))

(defn- text [toks] (.join (mapv (fn [t] (if (string? t) t (nth t 2))) toks) ""))

(defn- spans
  "The classed tokens of lang's highlighting of s, as [class text]."
  [lang s]
  (->> (highlight lang s)
       (remove string?)
       (mapv (fn [t] [(.slice (:class (nth t 1)) 3) (nth t 2)]))))

(def SAMPLES
  {"clojure" "(defn f [x] ;; c\n  {:a \"s\\\"\n t\" :b -1 :c nil #\"re\" ::k})"
   "bash" "echo \"a # b\" $HOME ${X} # real\nx#y; if [ -n \"$1\" ]; then exit 1; fi"
   "console" "$ npm test\nit's ok"
   "python" "@dec\ndef f(x):\n    \"\"\"doc\n    more\"\"\"\n    return None  # c\nf'x{y}'"
   "yaml" "on: push\nname: \"x\" # c\nok: true\nd: it's\nurl: http://x"
   "toml" "[tool]\nname = \"x\"\nv = '''a\nb'''\nn = 1.5"
   "dockerfile" "FROM node:20 AS b\nRUN npm run x $HOME # c"
   "js" "const u = \"http://x\"; // c\n/* a\nb */ let t = `a\nb${c}`; x = a-1"
   "ts" "interface A { b?: string } // c\n@Dec() class C {}"
   "java" "@Override public int f() { return 0x1F; } // c"
   "go" "func f() string { return `raw\n` } // c"
   "rust" "fn f<'a>(x: &'a str) -> u32 { 100u32 } // c"
   "c" "#include <stdio.h>\nint main() { return 0; } /* c */"
   "csharp" "public class A { string s = $\"x\"; } // c"
   "kotlin" "val s = \"\"\"a\nb\"\"\" // c\nfun f() = 1..10"
   "json" "{\"a\": -1.5e3, \"b\": \"c\", \"d\": [true, null]}"
   "sql" "SELECT a -- c\nFROM t WHERE b = '--x' /* d */ and c is null"
   "diff" "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n same"
   "html" "<!DOCTYPE html>\n<a href=\"x\" data-y=z>t &amp; <!-- c\n d --></a>\n<script>if (a<b) x()</script><br/>"
   "xml" "<?xml version=\"1.0\"?>\n<r a='1'/>"
   "svg" "<svg viewBox=\"0 0 1 1\"><rect/></svg>"
   "css" "@media (max-width: 600px) {\n  a:hover, .x #y { color: #fff !important; margin: -1.5em 0 }\n}\n/* c */ p { background: url(a.png); content: \"q\" }"})

(test "every language gives back its text exactly, also unterminated"
  (fn []
    (doseq [[lang s] SAMPLES]
      (assert/equal (text (highlight lang s)) s lang)
      (doseq [k (range (count s))]
        (assert/equal (text (highlight lang (subs s 0 k))) (subs s 0 k) (str lang " cut at " k))))))

(test "unknown languages, and none, are not highlighted"
  (fn []
    (doseq [l ["foo" "" nil "constructor" "toString" "__proto__"]]
      (assert/ok (nil? (highlight l "x")) (str l)))))

(test "fence names are case-insensitive and have aliases"
  (fn []
    (doseq [l ["Clojure" "clj" "cljs" "cljc" "EDN"]]
      (assert/deepEqual (spans l "nil") [["literal" "nil"]] l))
    (doseq [l ["sh" "shell" "zsh" "yml" "py" "javascript" "jsx" "typescript" "tsx"
               "rs" "cpp" "c++" "cs" "kt" "jsonc" "patch" "golang" "docker"]]
      (assert/ok (some? (highlight l "x")) l))))

(test "clojure: special forms, keywords, strings over lines, numbers, comments"
  (fn []
    (assert/deepEqual (spans "clojure" (get SAMPLES "clojure"))
                      [["keyword" "defn"] ["comment" ";; c"] ["attr" ":a"] ["string" "\"s\\\"\n t\""]
                       ["attr" ":b"] ["number" "-1"] ["attr" ":c"] ["literal" "nil"]
                       ["string" "#\"re\""] ["attr" "::k"]])
    (assert/deepEqual (spans "clojure" "(-> x inc-1)") [["keyword" "->"]])))

(test "bash: # starts a comment only after a space, not in strings; variables"
  (fn []
    (assert/deepEqual (spans "bash" (get SAMPLES "bash"))
                      [["string" "\"a # b\""] ["attr" "$HOME"] ["attr" "${X}"] ["comment" "# real"]
                       ["keyword" "if"] ["string" "\"$1\""] ["keyword" "then"] ["keyword" "exit"]
                       ["number" "1"] ["keyword" "fi"]])
    (assert/deepEqual (spans "console" (get SAMPLES "console")) [])))

(test "python: triple-quoted strings, decorators, keywords, prefixed strings"
  (fn []
    (assert/deepEqual (spans "python" (get SAMPLES "python"))
                      [["attr" "@dec"] ["keyword" "def"] ["string" "\"\"\"doc\n    more\"\"\""]
                       ["keyword" "return"] ["literal" "None"] ["comment" "# c"] ["string" "'x{y}'"]])))

(test "yaml and toml: keys, values, comments"
  (fn []
    (assert/deepEqual (spans "yaml" (get SAMPLES "yaml"))
                      [["attr" "on"] ["attr" "name"] ["string" "\"x\""] ["comment" "# c"]
                       ["attr" "ok"] ["literal" "true"] ["attr" "d"] ["attr" "url"]])
    (assert/deepEqual (spans "toml" (get SAMPLES "toml"))
                      [["attr" "name"] ["string" "\"x\""] ["attr" "v"] ["string" "'''a\nb'''"]
                       ["attr" "n"] ["number" "1.5"]])))

(test "dockerfile: instructions in capitals, shell variables"
  (fn []
    (assert/deepEqual (spans "dockerfile" (get SAMPLES "dockerfile"))
                      [["keyword" "FROM"] ["number" "20"] ["keyword" "AS"] ["keyword" "RUN"]
                       ["attr" "$HOME"] ["comment" "# c"]])))

(test "c-like: // in a string is no comment, block comments and template strings span lines"
  (fn []
    (assert/deepEqual (spans "js" (get SAMPLES "js"))
                      [["keyword" "const"] ["string" "\"http://x\""] ["comment" "// c"]
                       ["comment" "/* a\nb */"] ["keyword" "let"] ["string" "`a\nb${c}`"] ["number" "1"]])
    (assert/deepEqual (spans "ts" (get SAMPLES "ts"))
                      [["keyword" "interface"] ["comment" "// c"] ["attr" "@Dec"] ["keyword" "class"]])
    (assert/deepEqual (spans "java" (get SAMPLES "java"))
                      [["attr" "@Override"] ["keyword" "public"] ["keyword" "int"] ["keyword" "return"]
                       ["number" "0x1F"] ["comment" "// c"]])
    (assert/deepEqual (spans "go" (get SAMPLES "go"))
                      [["keyword" "func"] ["keyword" "return"] ["string" "`raw\n`"] ["comment" "// c"]])
    (assert/deepEqual (spans "rust" (get SAMPLES "rust"))
                      [["keyword" "fn"] ["number" "100u32"] ["comment" "// c"]])
    (assert/deepEqual (spans "c" (get SAMPLES "c"))
                      [["keyword" "#include"] ["keyword" "int"] ["keyword" "return"] ["number" "0"]
                       ["comment" "/* c */"]])
    (assert/deepEqual (spans "csharp" (get SAMPLES "csharp"))
                      [["keyword" "public"] ["keyword" "class"] ["keyword" "string"] ["string" "\"x\""]
                       ["comment" "// c"]])
    (assert/deepEqual (spans "kotlin" (get SAMPLES "kotlin"))
                      [["keyword" "val"] ["string" "\"\"\"a\nb\"\"\""] ["comment" "// c"]
                       ["keyword" "fun"] ["number" "1"] ["number" "10"]])))

(test "json: keys, values, literals"
  (fn []
    (assert/deepEqual (spans "json" (get SAMPLES "json"))
                      [["attr" "\"a\""] ["number" "-1.5e3"] ["attr" "\"b\""] ["string" "\"c\""]
                       ["attr" "\"d\""] ["literal" "true"] ["literal" "null"]])))

(test "sql: keywords in any case, -- in a string is no comment"
  (fn []
    (assert/deepEqual (spans "sql" (get SAMPLES "sql"))
                      [["keyword" "SELECT"] ["comment" "-- c"] ["keyword" "FROM"] ["keyword" "WHERE"]
                       ["string" "'--x'"] ["comment" "/* d */"] ["keyword" "and"] ["keyword" "is"]
                       ["literal" "null"]])))

(test "diff: whole lines by their first character"
  (fn []
    (assert/deepEqual (highlight "diff" (get SAMPLES "diff"))
                      [[:span {:class "hl-meta"} "--- a"] "\n" [:span {:class "hl-meta"} "+++ b"] "\n"
                       [:span {:class "hl-meta"} "@@ -1 +1 @@"] "\n" [:span {:class "hl-removed"} "-old"] "\n"
                       [:span {:class "hl-added"} "+new"] "\n same"])))

(test "markup: tags, attributes, values, entities, comments over lines, script left alone"
  (fn []
    (assert/deepEqual (spans "html" (get SAMPLES "html"))
                      [["meta" "<!DOCTYPE html>"] ["tag" "<a"] ["attr" "href"] ["string" "\"x\""]
                       ["attr" "data-y"] ["string" "z"] ["tag" ">"] ["literal" "&amp;"]
                       ["comment" "<!-- c\n d -->"] ["tag" "</a"] ["tag" ">"]
                       ["tag" "<script"] ["tag" ">"] ["tag" "</script"] ["tag" ">"] ["tag" "<br"] ["tag" "/>"]])
    (assert/ok (.includes (text (highlight "html" "<script>if (a<b) x()</script>")) "a<b"))
    (assert/deepEqual (spans "xml" (get SAMPLES "xml"))
                      [["meta" "<?xml version=\"1.0\"?>"] ["tag" "<r"] ["attr" "a"] ["string" "'1'"] ["tag" "/>"]])))

(test "css: selectors, properties, values, at-rules, url()"
  (fn []
    (assert/deepEqual (spans "css" (get SAMPLES "css"))
                      [["keyword" "@media"] ["number" "600px"]
                       ["tag" "a"] ["keyword" ":hover"] ["tag" ".x"] ["tag" "#y"]
                       ["attr" "color"] ["number" "#fff"] ["keyword" "!important"]
                       ["attr" "margin"] ["number" "-1.5em"] ["number" "0"]
                       ["comment" "/* c */"] ["tag" "p"] ["attr" "background"] ["string" "a.png"]
                       ["attr" "content"] ["string" "\"q\""]])
    (assert/deepEqual (spans "css" ":root { --bg: #fafafa; }")
                      [["keyword" ":root"] ["attr" "--bg"] ["number" "#fafafa"]])))

(test "clojure: \\\" and \\; are characters, not a string or a comment"
  (fn []
    (assert/deepEqual (spans "clojure" "(= c \\\")\n(defn f [] 1)")
                      [["string" "\\\""] ["keyword" "defn"] ["number" "1"]])
    (assert/deepEqual (spans "clojure" "(str \\; \\newline \"a\")")
                      [["string" "\\;"] ["string" "\\newline"] ["string" "\"a\""]])))

(test "rust strings and java text blocks span lines"
  (fn []
    (assert/deepEqual (spans "rust" "let s = \"one\ntwo\";\nlet n = 1;")
                      [["keyword" "let"] ["string" "\"one\ntwo\""] ["keyword" "let"] ["number" "1"]])
    (assert/deepEqual (spans "java" "s = \"\"\"\n  Hello \"x\"\n  \"\"\";")
                      [["string" "\"\"\"\n  Hello \"x\"\n  \"\"\""]])))

(test "diff: --- and +++ inside a hunk are removed and added lines"
  (fn []
    (assert/deepEqual (spans "diff" "@@ -1,3 +0,0 @@\n----\n-title: x\n----")
                      [["meta" "@@ -1,3 +0,0 @@"] ["removed" "----"] ["removed" "-title: x"] ["removed" "----"]])
    (assert/deepEqual (spans "diff" "--- a\n+++ b\n@@ -1 +0,0 @@\n-x\n--- c\n+++ d\n@@ -0,0 +1 @@\n+y")
                      [["meta" "--- a"] ["meta" "+++ b"] ["meta" "@@ -1 +0,0 @@"] ["removed" "-x"]
                       ["meta" "--- c"] ["meta" "+++ d"] ["meta" "@@ -0,0 +1 @@"] ["added" "+y"]])
    (assert/deepEqual (spans "diff" "@@ -1,2 +1,2 @@\n--- x\n+++ y\n same\nindex z")
                      [["meta" "@@ -1,2 +1,2 @@"] ["removed" "--- x"] ["added" "+++ y"] ["meta" "index z"]])
    (assert/deepEqual (spans "diff" "----\n-a\n+b") [["removed" "----"] ["removed" "-a"] ["added" "+b"]])))

(test "sql: a backslash escapes nothing"
  (fn []
    (assert/deepEqual (spans "sql" "SELECT 'C:\\' FROM t")
                      [["keyword" "SELECT"] ["string" "'C:\\'"] ["keyword" "FROM"]])))

(test "css: braces in strings and comments don't decide selector or property"
  (fn []
    (assert/deepEqual (spans "css" "a { content: \"{\"; /* { */ color: red; }")
                      [["tag" "a"] ["attr" "content"] ["string" "\"{\""] ["comment" "/* { */"] ["attr" "color"]])))

(test "python and toml: # starts a comment anywhere outside a string"
  (fn []
    (assert/deepEqual (spans "python" "x=1#c") [["number" "1"] ["comment" "#c"]])
    (assert/deepEqual (spans "toml" "a=1#c") [["attr" "a"] ["number" "1"] ["comment" "#c"]])))

(test "html with many style tags stays linear"
  (fn []
    (let [s (.repeat "<a href=\"x\">t</a><style>a{}</style>\n" 20000)
          t0 (js/Date.now)]
      (assert/equal (text (highlight "html" s)) s)
      (assert/ok (< (- (js/Date.now) t0) 1500) "20,000 style tags in under 1.5 s"))))

(test "raw and verbatim strings: a backslash escapes nothing"
  (fn []
    (assert/deepEqual (spans "go" "p := `C:\\dir\\`\nfunc f() { return 1 }")
                      [["string" "`C:\\dir\\`"] ["keyword" "func"] ["keyword" "return"] ["number" "1"]])
    (assert/deepEqual (spans "csharp" "var p = @\"C:\\\"; int y = 1;")
                      [["keyword" "var"] ["string" "@\"C:\\\""] ["keyword" "int"] ["number" "1"]])
    (assert/deepEqual (spans "rust" "let s = r#\"a\"b\"#; let t = r\"C:\\\"; let n = 1;")
                      [["keyword" "let"] ["string" "r#\"a\"b\"#"] ["keyword" "let"] ["string" "r\"C:\\\""]
                       ["keyword" "let"] ["number" "1"]])))

(test "js: regex literals after an operator or keyword, division elsewhere"
  (fn []
    (assert/deepEqual (spans "js" "const re = /`/g;\nfunction f() { return 1 }")
                      [["keyword" "const"] ["string" "/`/g"] ["keyword" "function"] ["keyword" "return"] ["number" "1"]])
    (assert/deepEqual (spans "ts" "s.split(/[/\"]/); return /x\\//.test(a)")
                      [["string" "/[/\"]/"] ["keyword" "return"] ["string" "/x\\//"]])
    (assert/deepEqual (spans "js" "x = a / b / 2") [["number" "2"]])))

(test "yaml: | and > block scalars are text"
  (fn []
    (assert/deepEqual (spans "yaml" "run: |\n  # not a comment\n\n  echo \"x\nnext: 1\n- >-\n  a: b\n")
                      [["attr" "run"] ["string" "|\n  # not a comment\n\n  echo \"x"] ["attr" "next"]
                       ["number" "1"] ["string" ">-\n  a: b"]])
    (assert/deepEqual (spans "yaml" "a: b|c") [["attr" "a"]])))

(test "clojure: ratios are numbers"
  (fn []
    (assert/deepEqual (spans "clojure" "(* 22/7 -1/2 r)") [["number" "22/7"] ["number" "-1/2"]])))
