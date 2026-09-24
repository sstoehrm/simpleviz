(ns themes
  "The built-in color themes as plain data, shared by the server, which
  validates a graph's :theme against them (graph/normalize), and the
  page, which paints with them — squint compiles this file too; there
  keywords are strings, so the maps read as string-keyed objects.
  Sources and licenses of the borrowed palettes: THIRD-PARTY-NOTICES.md.")

(def NAMES
  "The built-in themes, in documentation order."
  [:light :dark :print :high-contrast :blueprint :paper
   :solarized-light :solarized-dark :nord :dracula :carbonfox :one-dark])

(def KEYS
  "Every theme key, in documentation order: page chrome, canvas painter,
  compare-mode diff marks, :state marks, type colors."
  [:bg :panel :panel-border :panel-divider :text :text-strong :text-muted
   :text-dim :hover :hover-plain :accent :shadow
   :node-fill :node-stroke :edge :arrow :sub :label :btn-fill
   :diff-added :diff-modified :diff-removed
   :state-new :state-in-progress :state-blocked :state-done
   :node-saturation :node-lightness :box-saturation :box-lightness
   :box-fill-alpha :neutral-node-lightness :neutral-box-lightness])

(def KEY-KINDS
  "The value each key takes: :color (a CSS color string), :percent (a
  number 0-100) or :alpha (a number 0-1)."
  {:bg :color :panel :color :panel-border :color :panel-divider :color
   :text :color :text-strong :color :text-muted :color :text-dim :color
   :hover :color :hover-plain :color :accent :color :shadow :color
   :node-fill :color :node-stroke :color :edge :color :arrow :color
   :sub :color :label :color :btn-fill :color
   :diff-added :color :diff-modified :color :diff-removed :color
   :state-new :color :state-in-progress :color :state-blocked :color :state-done :color
   :node-saturation :percent :node-lightness :percent
   :box-saturation :percent :box-lightness :percent
   :box-fill-alpha :alpha
   :neutral-node-lightness :percent :neutral-box-lightness :percent})

(def CSS-KEYS
  "The keys the page mirrors into CSS custom properties, as --<key>."
  [:bg :panel :panel-border :panel-divider :text :text-strong :text-muted
   :text-dim :hover :hover-plain :accent :shadow
   :diff-added :diff-modified :diff-removed])

(def THEMES
  "Name -> complete theme (every key in KEYS)."
  {:light
   {:bg "#fafafa" :panel "#fff" :panel-border "#ddd" :panel-divider "#eee"
    :text "#374151" :text-strong "#000" :text-muted "#6b7280" :text-dim "#9ca3af"
    :hover "#f1f5ff" :hover-plain "#f0f0f0" :accent "#2563eb" :shadow "rgba(0, 0, 0, .06)"
    :node-fill "#fff" :node-stroke "#ddd" :edge "#555" :arrow "#555"
    :sub "#888" :label "#444" :btn-fill "#ffffffcc"
    :diff-added "#0ca30c" :diff-modified "#b45309" :diff-removed "#d03b3b"
    :state-new "#6b7280" :state-in-progress "#2563eb" :state-blocked "#d03b3b" :state-done "#0ca30c"
    :node-saturation 65 :node-lightness 38 :box-saturation 45 :box-lightness 55
    :box-fill-alpha 0.1 :neutral-node-lightness 40 :neutral-box-lightness 65}

   :dark
   {:bg "#111827" :panel "#1f2937" :panel-border "#374151" :panel-divider "#374151"
    :text "#d1d5db" :text-strong "#fff" :text-muted "#9ca3af" :text-dim "#6b7280"
    :hover "#1e3a8a33" :hover-plain "#374151" :accent "#60a5fa" :shadow "rgba(0, 0, 0, .4)"
    :node-fill "#1f2937" :node-stroke "#4b5563" :edge "#9ca3af" :arrow "#9ca3af"
    :sub "#9ca3af" :label "#d1d5db" :btn-fill "#1f2937cc"
    :diff-added "#22c55e" :diff-modified "#fab219" :diff-removed "#f87171"
    :state-new "#9ca3af" :state-in-progress "#60a5fa" :state-blocked "#f87171" :state-done "#22c55e"
    :node-saturation 65 :node-lightness 72 :box-saturation 45 :box-lightness 55
    :box-fill-alpha 0.1 :neutral-node-lightness 65 :neutral-box-lightness 65}

   :print
   {:bg "#ffffff" :panel "#ffffff" :panel-border "#cccccc" :panel-divider "#e5e5e5"
    :text "#222222" :text-strong "#000000" :text-muted "#555555" :text-dim "#888888"
    :hover "#f2f2f2" :hover-plain "#eeeeee" :accent "#000000" :shadow "rgba(0, 0, 0, .08)"
    :node-fill "#ffffff" :node-stroke "#666666" :edge "#333333" :arrow "#333333"
    :sub "#666666" :label "#222222" :btn-fill "#ffffffcc"
    :diff-added "#1a7f1a" :diff-modified "#a15c00" :diff-removed "#b42318"
    :state-new "#666666" :state-in-progress "#1f4fbf" :state-blocked "#b42318" :state-done "#1a7f1a"
    :node-saturation 55 :node-lightness 30 :box-saturation 30 :box-lightness 40
    :box-fill-alpha 0 :neutral-node-lightness 25 :neutral-box-lightness 45}

   :high-contrast
   {:bg "#ffffff" :panel "#ffffff" :panel-border "#000000" :panel-divider "#000000"
    :text "#000000" :text-strong "#000000" :text-muted "#333333" :text-dim "#555555"
    :hover "#e6f0ff" :hover-plain "#e6e6e6" :accent "#0040ff" :shadow "rgba(0, 0, 0, .25)"
    :node-fill "#ffffff" :node-stroke "#000000" :edge "#000000" :arrow "#000000"
    :sub "#333333" :label "#000000" :btn-fill "#ffffff"
    :diff-added "#007a00" :diff-modified "#b35900" :diff-removed "#cc0000"
    :state-new "#444444" :state-in-progress "#0040ff" :state-blocked "#cc0000" :state-done "#007a00"
    :node-saturation 100 :node-lightness 22 :box-saturation 100 :box-lightness 22
    :box-fill-alpha 0.08 :neutral-node-lightness 0 :neutral-box-lightness 20}

   :blueprint
   {:bg "#0d3b66" :panel "#11487a" :panel-border "#3d6f9e" :panel-divider "#2a5d8c"
    :text "#d7ecff" :text-strong "#ffffff" :text-muted "#a9cbe8" :text-dim "#7fa7cc"
    :hover "#ffffff1a" :hover-plain "#1a5a91" :accent "#7fdbff" :shadow "rgba(0, 0, 0, .35)"
    :node-fill "#0f4474" :node-stroke "#9fd3ff" :edge "#bfe6ff" :arrow "#bfe6ff"
    :sub "#a9cbe8" :label "#e6f4ff" :btn-fill "#0f4474cc"
    :diff-added "#7ee787" :diff-modified "#ffd166" :diff-removed "#ff8a8a"
    :state-new "#a9cbe8" :state-in-progress "#7fdbff" :state-blocked "#ff8a8a" :state-done "#7ee787"
    :node-saturation 80 :node-lightness 80 :box-saturation 60 :box-lightness 75
    :box-fill-alpha 0.12 :neutral-node-lightness 90 :neutral-box-lightness 80}

   :paper
   {:bg "#f7f1e3" :panel "#fbf7ee" :panel-border "#d9ccb0" :panel-divider "#e8dfca"
    :text "#4a3b2a" :text-strong "#2b1f12" :text-muted "#7a6650" :text-dim "#a39075"
    :hover "#efe4cc" :hover-plain "#ede3cf" :accent "#8b4513" :shadow "rgba(74, 59, 42, .12)"
    :node-fill "#fdfaf3" :node-stroke "#cdbd9c" :edge "#6b5842" :arrow "#6b5842"
    :sub "#8a7558" :label "#4a3b2a" :btn-fill "#fdfaf3cc"
    :diff-added "#4f7a28" :diff-modified "#b0701c" :diff-removed "#a8322d"
    :state-new "#8a7558" :state-in-progress "#3b6ea5" :state-blocked "#a8322d" :state-done "#4f7a28"
    :node-saturation 45 :node-lightness 35 :box-saturation 35 :box-lightness 42
    :box-fill-alpha 0.1 :neutral-node-lightness 35 :neutral-box-lightness 55}

   ;; Solarized, © 2011 Ethan Schoonover (MIT)
   :solarized-light
   {:bg "#fdf6e3" :panel "#fdf6e3" :panel-border "#93a1a1" :panel-divider "#eee8d5"
    :text "#586e75" :text-strong "#073642" :text-muted "#657b83" :text-dim "#93a1a1"
    :hover "#eee8d5" :hover-plain "#eee8d5" :accent "#268bd2" :shadow "rgba(0, 43, 54, .1)"
    :node-fill "#fdf6e3" :node-stroke "#93a1a1" :edge "#586e75" :arrow "#586e75"
    :sub "#657b83" :label "#586e75" :btn-fill "#eee8d5cc"
    :diff-added "#859900" :diff-modified "#b58900" :diff-removed "#dc322f"
    :state-new "#93a1a1" :state-in-progress "#268bd2" :state-blocked "#dc322f" :state-done "#859900"
    :node-saturation 70 :node-lightness 32 :box-saturation 50 :box-lightness 40
    :box-fill-alpha 0.1 :neutral-node-lightness 40 :neutral-box-lightness 60}

   :solarized-dark
   {:bg "#002b36" :panel "#073642" :panel-border "#586e75" :panel-divider "#0d4452"
    :text "#93a1a1" :text-strong "#eee8d5" :text-muted "#839496" :text-dim "#586e75"
    :hover "#268bd233" :hover-plain "#0d4452" :accent "#268bd2" :shadow "rgba(0, 0, 0, .4)"
    :node-fill "#073642" :node-stroke "#586e75" :edge "#93a1a1" :arrow "#93a1a1"
    :sub "#839496" :label "#93a1a1" :btn-fill "#073642cc"
    :diff-added "#859900" :diff-modified "#b58900" :diff-removed "#dc322f"
    :state-new "#839496" :state-in-progress "#268bd2" :state-blocked "#dc322f" :state-done "#859900"
    :node-saturation 65 :node-lightness 70 :box-saturation 45 :box-lightness 55
    :box-fill-alpha 0.12 :neutral-node-lightness 65 :neutral-box-lightness 55}

   ;; Nord, © 2016-present Sven Greb (MIT)
   :nord
   {:bg "#2e3440" :panel "#3b4252" :panel-border "#4c566a" :panel-divider "#434c5e"
    :text "#d8dee9" :text-strong "#eceff4" :text-muted "#a6b0c3" :text-dim "#7b88a1"
    :hover "#88c0d026" :hover-plain "#434c5e" :accent "#88c0d0" :shadow "rgba(0, 0, 0, .35)"
    :node-fill "#3b4252" :node-stroke "#4c566a" :edge "#81a1c1" :arrow "#81a1c1"
    :sub "#a6b0c3" :label "#e5e9f0" :btn-fill "#3b4252cc"
    :diff-added "#a3be8c" :diff-modified "#ebcb8b" :diff-removed "#bf616a"
    :state-new "#7b88a1" :state-in-progress "#88c0d0" :state-blocked "#bf616a" :state-done "#a3be8c"
    :node-saturation 45 :node-lightness 72 :box-saturation 35 :box-lightness 60
    :box-fill-alpha 0.12 :neutral-node-lightness 75 :neutral-box-lightness 55}

   ;; Dracula, © 2023 Dracula Theme (MIT)
   :dracula
   {:bg "#282a36" :panel "#343746" :panel-border "#44475a" :panel-divider "#44475a"
    :text "#f8f8f2" :text-strong "#ffffff" :text-muted "#b6b9cc" :text-dim "#6272a4"
    :hover "#bd93f933" :hover-plain "#44475a" :accent "#ff79c6" :shadow "rgba(0, 0, 0, .4)"
    :node-fill "#343746" :node-stroke "#6272a4" :edge "#bd93f9" :arrow "#bd93f9"
    :sub "#b6b9cc" :label "#f8f8f2" :btn-fill "#343746cc"
    :diff-added "#50fa7b" :diff-modified "#ffb86c" :diff-removed "#ff5555"
    :state-new "#6272a4" :state-in-progress "#8be9fd" :state-blocked "#ff5555" :state-done "#50fa7b"
    :node-saturation 85 :node-lightness 75 :box-saturation 60 :box-lightness 65
    :box-fill-alpha 0.12 :neutral-node-lightness 85 :neutral-box-lightness 60}

   ;; carbonfox from nightfox.nvim, © 2021 James Simpson (MIT)
   :carbonfox
   {:bg "#161616" :panel "#202020" :panel-border "#353535" :panel-divider "#2a2a2a"
    :text "#f2f4f8" :text-strong "#ffffff" :text-muted "#b6b8bb" :text-dim "#7b7c7e"
    :hover "#78a9ff26" :hover-plain "#2a2a2a" :accent "#78a9ff" :shadow "rgba(0, 0, 0, .5)"
    :node-fill "#202020" :node-stroke "#525253" :edge "#9da0a5" :arrow "#9da0a5"
    :sub "#b6b8bb" :label "#dfdfe0" :btn-fill "#202020cc"
    :diff-added "#25be6a" :diff-modified "#be95ff" :diff-removed "#ee5396"
    :state-new "#7b7c7e" :state-in-progress "#78a9ff" :state-blocked "#ee5396" :state-done "#25be6a"
    :node-saturation 90 :node-lightness 74 :box-saturation 55 :box-lightness 62
    :box-fill-alpha 0.12 :neutral-node-lightness 80 :neutral-box-lightness 55}

   ;; Atom One Dark, © GitHub Inc. (MIT)
   :one-dark
   {:bg "#282c34" :panel "#21252b" :panel-border "#3e4451" :panel-divider "#333842"
    :text "#abb2bf" :text-strong "#d7dae0" :text-muted "#828997" :text-dim "#5c6370"
    :hover "#61afef26" :hover-plain "#333842" :accent "#61afef" :shadow "rgba(0, 0, 0, .4)"
    :node-fill "#21252b" :node-stroke "#3e4451" :edge "#828997" :arrow "#828997"
    :sub "#828997" :label "#abb2bf" :btn-fill "#21252bcc"
    :diff-added "#98c379" :diff-modified "#e5c07b" :diff-removed "#e06c75"
    :state-new "#5c6370" :state-in-progress "#61afef" :state-blocked "#e06c75" :state-done "#98c379"
    :node-saturation 60 :node-lightness 68 :box-saturation 45 :box-lightness 58
    :box-fill-alpha 0.12 :neutral-node-lightness 70 :neutral-box-lightness 50}})
