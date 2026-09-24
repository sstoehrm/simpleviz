(ns simpleviz.svg-test
  (:require ["node:test" :refer [test]]
            ["node:assert/strict$default" :as assert]
            [simpleviz.svg :as svg]))

;; Every expectation below is derived by hand from the canvas semantics
;; the recorder mimics — none is a snapshot of its own output.

(defn- fake-measure [text _font] (* 7 (count text)))

(defn- rec [] (svg/recorder fake-measure))

(test "roundRect is lines plus one corner arc per corner"
  (fn []
    (let [r (rec)]
      (.beginPath r)
      (.roundRect r 10 20 100 40 6)
      (set! (.-fillStyle r) "#fff")
      (.fill r)
      (assert/equal (svg/markup r)
                    (str "<path d=\"M16 20L104 20A6 6 0 0 1 110 26L110 54A6 6 0 0 1 104 60"
                         "L16 60A6 6 0 0 1 10 54L10 26A6 6 0 0 1 16 20Z\" fill=\"#fff\"/>")))))

(test "roundRect shrinks a radius that doesn't fit, like canvas"
  (fn []
    (let [r (rec)]
      ;; 8 tall: two radii of 6 need 12, so both scale to 4
      (.beginPath r)
      (.roundRect r 0 0 10 8 6)
      (.fill r)
      (assert/equal (svg/markup r)
                    (str "<path d=\"M4 0L6 0A4 4 0 0 1 10 4L10 4A4 4 0 0 1 6 8"
                         "L4 8A4 4 0 0 1 0 4L0 4A4 4 0 0 1 4 0Z\" fill=\"#000\"/>")))))

(test "a fill then a stroke of the same path is one element"
  (fn []
    (let [r (rec)]
      (.beginPath r)
      (.rect r 1 2 30 20)
      (set! (.-fillStyle r) "#fff")
      (.fill r)
      (set! (.-strokeStyle r) "#ddd")
      (set! (.-lineWidth r) 1.5)
      (.stroke r)
      (assert/equal (svg/markup r)
                    "<path d=\"M1 2L31 2L31 22L1 22Z\" fill=\"#fff\" stroke=\"#ddd\" stroke-width=\"1.5\"/>"))))

(test "a full circle is two half arcs"
  (fn []
    (let [r (rec)]
      (.beginPath r)
      (.arc r 50 60 5 0 (* 2 js/Math.PI))
      (set! (.-fillStyle r) "#0ca30c")
      (.fill r)
      (assert/equal (svg/markup r)
                    "<path d=\"M55 60A5 5 0 0 1 45 60A5 5 0 0 1 55 60\" fill=\"#0ca30c\"/>"))))

(test "arc after moveTo first draws a line to its start; closePath closes"
  (fn []
    ;; the in-progress state mark: a half disc from the centre
    (let [r (rec)]
      (.beginPath r)
      (.moveTo r 50 60)
      (.arc r 50 60 5 (* -0.5 js/Math.PI) (* 0.5 js/Math.PI))
      (.closePath r)
      (set! (.-fillStyle r) "#2563eb")
      (.fill r)
      (assert/equal (svg/markup r)
                    "<path d=\"M50 60L50 55A5 5 0 0 1 50 65Z\" fill=\"#2563eb\"/>"))))

(test "arcs over half a turn set the large-arc flag; anticlockwise clears sweep"
  (fn []
    (let [r (rec)]
      (.beginPath r)
      (.arc r 0 0 10 0 (* 1.5 js/Math.PI))
      (.stroke r)
      (.beginPath r)
      ;; anticlockwise from 0 to a quarter turn is three quarters round
      (.arc r 0 0 10 0 (* 0.5 js/Math.PI) true)
      (.stroke r)
      (assert/equal (svg/markup r)
                    (str "<path d=\"M10 0A10 10 0 1 1 0 -10\" fill=\"none\" stroke=\"#000\" stroke-width=\"1\"/>\n"
                         "<path d=\"M10 0A10 10 0 1 0 0 10\" fill=\"none\" stroke=\"#000\" stroke-width=\"1\"/>")))))

(test "translate and rotate move the points added under them; restore undoes both"
  (fn []
    ;; an arrowhead pointing down at (100, 50): rotate(pi/2) maps (x, y)
    ;; to (-y, x), then the translation is added
    (let [r (rec)]
      (set! (.-fillStyle r) "#111")
      (.save r)
      (.translate r 100 50)
      (.rotate r (/ js/Math.PI 2))
      (.beginPath r)
      (.moveTo r 0 0)
      (.lineTo r -8 4)
      (.lineTo r -8 -4)
      (.closePath r)
      (set! (.-fillStyle r) "#555")
      (.fill r)
      (.restore r)
      (assert/equal (.-fillStyle r) "#111")
      (.beginPath r)
      (.moveTo r 1 2)
      (.lineTo r 3 4)
      (.stroke r)
      (assert/equal (svg/markup r)
                    (str "<path d=\"M100 50L96 42L104 42Z\" fill=\"#555\"/>\n"
                         "<path d=\"M1 2L3 4\" fill=\"none\" stroke=\"#000\" stroke-width=\"1\"/>")))))

(test "fillText maps textAlign to text-anchor"
  (fn []
    (doseq [[align anchor] [["left" "start"] ["start" "start"] ["center" "middle"]
                            ["right" "end"] ["end" "end"]]]
      (let [r (rec)]
        (set! (.-textAlign r) align)
        (.fillText r "x" 1 2)
        (assert/equal (svg/markup r)
                      (str "<text x=\"1\" y=\"2\" text-anchor=\"" anchor "\" font-size=\"10\""
                           " font-family=\"sans-serif\" fill=\"#000\">x</text>"))))))

(test "fillText parses the font, rounds to 2 decimals and escapes the text"
  (fn []
    (let [r (rec)]
      (set! (.-font r) "bold 14px system-ui, sans-serif")
      (set! (.-fillStyle r) "#333")
      (set! (.-textAlign r) "center")
      (.fillText r "a<b & \"c\">" 10.004 19.996)
      (set! (.-font r) "11px system-ui, sans-serif")
      (.fillText r "(type)" 1.234 4.5678)
      (assert/equal (svg/markup r)
                    (str "<text x=\"10\" y=\"20\" text-anchor=\"middle\" font-weight=\"bold\" font-size=\"14\""
                         " font-family=\"system-ui, sans-serif\" fill=\"#333\">a&lt;b &amp; &quot;c&quot;&gt;</text>\n"
                         "<text x=\"1.23\" y=\"4.57\" text-anchor=\"middle\" font-size=\"11\""
                         " font-family=\"system-ui, sans-serif\" fill=\"#333\">(type)</text>")))))

(test "measureText measures with the current font"
  (fn []
    (let [calls (atom [])
          r (svg/recorder (fn [text font] (swap! calls conj [text font]) 42))]
      (set! (.-font r) "bold 13px system-ui, sans-serif")
      (assert/equal (.-width (.measureText r "abc")) 42)
      (assert/deepEqual @calls [["abc" "bold 13px system-ui, sans-serif"]]))))

(test "strokeText then fillText: the halo stays under the label"
  (fn []
    (let [r (rec)]
      (set! (.-textAlign r) "center")
      (set! (.-font r) "11px system-ui, sans-serif")
      (set! (.-lineWidth r) 3)
      (set! (.-strokeStyle r) "#fafafa")
      (.strokeText r "calls" 5 6)
      (set! (.-fillStyle r) "#444")
      (.fillText r "calls" 5 6)
      (assert/equal (svg/markup r)
                    (str "<text x=\"5\" y=\"6\" text-anchor=\"middle\" font-size=\"11\""
                         " font-family=\"system-ui, sans-serif\" fill=\"none\" stroke=\"#fafafa\""
                         " stroke-width=\"3\" stroke-linejoin=\"round\">calls</text>\n"
                         "<text x=\"5\" y=\"6\" text-anchor=\"middle\" font-size=\"11\""
                         " font-family=\"system-ui, sans-serif\" fill=\"#444\">calls</text>")))))

(test "globalAlpha other than 1 becomes opacity on each paint"
  (fn []
    ;; translucent fill and stroke stay two elements: one element's
    ;; opacity would composite them as a group, which canvas doesn't
    (let [r (rec)]
      (set! (.-globalAlpha r) 0.45)
      (.beginPath r)
      (.rect r 0 0 10 5)
      (.fill r)
      (.stroke r)
      (set! (.-globalAlpha r) 1)
      (.fillText r "t" 0 0)
      (assert/equal (svg/markup r)
                    (str "<path d=\"M0 0L10 0L10 5L0 5Z\" fill=\"#000\" opacity=\"0.45\"/>\n"
                         "<path d=\"M0 0L10 0L10 5L0 5Z\" fill=\"none\" stroke=\"#000\" stroke-width=\"1\" opacity=\"0.45\"/>\n"
                         "<text x=\"0\" y=\"0\" text-anchor=\"start\" font-size=\"10\""
                         " font-family=\"sans-serif\" fill=\"#000\">t</text>")))))

(test "setLineDash dashes strokes until it is reset to solid"
  (fn []
    (let [r (rec)]
      (.setLineDash r [5 4])
      (.beginPath r)
      (.moveTo r 0 0)
      (.lineTo r 10 0)
      (.stroke r)
      ;; a dash never applies to fills
      (.fill r)
      (.setLineDash r [])
      (.beginPath r)
      (.moveTo r 0 5)
      (.lineTo r 10 5)
      (.stroke r)
      (assert/equal (svg/markup r)
                    (str "<path d=\"M0 0L10 0\" fill=\"none\" stroke=\"#000\" stroke-width=\"1\" stroke-dasharray=\"5 4\"/>\n"
                         "<path d=\"M0 0L10 0\" fill=\"#000\"/>\n"
                         "<path d=\"M0 5L10 5\" fill=\"none\" stroke=\"#000\" stroke-width=\"1\"/>")))))

(test "restore brings back the line dash that was set at save"
  (fn []
    (let [r (rec)]
      (.setLineDash r [5 4])
      (.save r)
      (.setLineDash r [2 1])
      (.beginPath r)
      (.moveTo r 0 0)
      (.lineTo r 10 0)
      (.stroke r)
      (.restore r)
      (.stroke r)
      (assert/equal (svg/markup r)
                    (str "<path d=\"M0 0L10 0\" fill=\"none\" stroke=\"#000\" stroke-width=\"1\" stroke-dasharray=\"2 1\"/>\n"
                         "<path d=\"M0 0L10 0\" fill=\"none\" stroke=\"#000\" stroke-width=\"1\" stroke-dasharray=\"5 4\"/>")))))

(test "text whose whitespace SVG would collapse or trim keeps it, as canvas does"
  (fn []
    ;; canvas draws every space and turns a newline or tab into one;
    ;; SVG does too only under xml:space="preserve" on the <text> itself
    (doseq [[txt preserve?] [["a b" false] ["a  b" true] [" a" true] ["a " true]
                             ["a\nb" true] ["a\tb" true]]]
      (let [r (rec)]
        (.fillText r txt 0 0)
        (assert/equal (.includes (svg/markup r) " xml:space=\"preserve\"") preserve? txt)))))

(test "text keeps carriage returns and replaces characters XML forbids"
  (fn []
    (let [r (rec)]
      (.fillText r "a\rb\u0007c" 0 0)
      (assert/ok (.includes (svg/markup r) ">a&#13;b\uFFFDc</text>")))))

(test "svg-document: declaration, viewBox, metadata, background, then the drawing"
  (fn []
    (let [r (rec)]
      (.beginPath r)
      (.rect r 1 2 3 4)
      (set! (.-fillStyle r) "#fff")
      (.fill r)
      (assert/equal
       (svg/svg-document r {:width 120.456 :height 80 :background "#fafafa"
                            :sources [["simpleviz-edn-old" "{:a \"]]>\"}"]
                                      ["simpleviz-edn-new" "{:b \"<x>\" :c \"&\"}"]]})
       (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120.46\" height=\"80\""
            " viewBox=\"0 0 120.46 80\" stroke-miterlimit=\"10\">\n"
            "<metadata xmlns:simpleviz=\"https://github.com/sstoehrm/simpleviz\">\n"
            "<simpleviz:source key=\"simpleviz-edn-old\">{:a &quot;]]&gt;&quot;}</simpleviz:source>\n"
            "<simpleviz:source key=\"simpleviz-edn-new\">{:b &quot;&lt;x&gt;&quot; :c &quot;&amp;&quot;}</simpleviz:source>\n"
            "</metadata>\n"
            "<rect width=\"120.46\" height=\"80\" fill=\"#fafafa\"/>\n"
            "<path d=\"M1 2L4 2L4 6L1 6Z\" fill=\"#fff\"/>\n"
            "</svg>\n")))))

(test "svg-document with a single source uses the plain key"
  (fn []
    (let [doc (svg/svg-document (rec) {:width 10 :height 10 :background "#111827"
                                        :sources [["simpleviz-edn" "{:nodes {}}"]]})]
      (assert/ok (.includes doc "<simpleviz:source key=\"simpleviz-edn\">{:nodes {}}</simpleviz:source>"))
      (assert/ok (.includes doc "<rect width=\"10\" height=\"10\" fill=\"#111827\"/>")))))

(test "svg-document without sources still has its (empty) metadata"
  (fn []
    ;; every source fetch failed: the export goes out without EDN
    (assert/equal (svg/svg-document (rec) {:width 10 :height 20 :background "#fafafa" :sources []})
                  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                       "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"20\""
                       " viewBox=\"0 0 10 20\" stroke-miterlimit=\"10\">\n"
                       "<metadata xmlns:simpleviz=\"https://github.com/sstoehrm/simpleviz\">\n"
                       "</metadata>\n"
                       "<rect width=\"10\" height=\"20\" fill=\"#fafafa\"/>\n"
                       "</svg>\n"))))
