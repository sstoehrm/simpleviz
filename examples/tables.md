# Tables that calculate

A table cell starting with `=(` is a formula: plain Clojure, run when the page renders.
Hover a result to see its formula. A `simpleviz-table` block right above a table
formats its cells; the page hides it, so open Edit, top right, to read both.

## Formulas in the table

Columns are letters, rows the body rows from 1: `(sum :b 1 2)` adds rows 1 and 2 of
column b, `(sum :b)` the whole column. A formula cell is in no range, so the total
can sum its own column.

```simpleviz-table
[:b 1 4] #(if (> %1 500) {:color "crimson"} %1)
[:a 1 4 :b 1 4 :c 1 4] #(if (even? (:row %2)) {:background "hsl(220 30% 50% / 0.12)"} %1)
[:b] #(format "%.2f €" (double %1))
[:c] #(format "%.0f %%" (* 100.0 %1))
```

| item      | per month | share |
|-----------|----------:|------:|
| rent      | 950       | =(/ (sum :b 1) (sum :b)) |
| food      | 420       | =(/ (sum :b 2) (sum :b)) |
| transport | 85        | =(/ (sum :b 3) (sum :b)) |
| fun       | 160       | =(/ (sum :b 4) (sum :b)) |
| total     | =(sum :b) |       |

The block holds pairs of a range and a fn, applied top to bottom. A fn gets the cell's
value, and as its second argument the cell's place, `{:row 2 :col :b :raw "420"}`: the
second line shades the even rows. A map colours the cell and keeps its value; a string
or number replaces it. Formulas always see the plain numbers.

## Formulas over a data file

The block's first line links `sales.csv`, next to this page. Now the formulas work on its
records, not on the table: the csv's columns are a to d (region, product, units,
price), its rows the records from 1. None of them shows unless a formula asks for it.

```simpleviz-table
>> [the sales](sales.csv)
[:b 4] #(format "%.2f €" %1)
[:b 5] #(format "%.2f €" (double %1))
[:b 6] #(if (pos? %1) {:color "crimson"} %1)
```

| what           | value |
|----------------|------:|
| records        | =(count (rows)) |
| units sold     | =(sum :c) |
| tea units      | =(reduce + (keep #(when (= "tea" (:b %)) (:c %)) (rows))) |
| average price  | =(avg :d) |
| revenue        | =(reduce + (for [r (rows) :when (:c r)] (* (:c r) (:d r)))) |
| missing units  | =(count (remove :c (rows))) |

South's coffee has no units or price: `sum` and `avg` skip the gap, plain Clojure has to
allow for it, as the revenue does. `(rows)` is every record as a map,
`{:a "North" :b "tea" :c 120 :d 3.5}`.

The records come in the file's order, so ranges pick a region; a fn may return any text:

```simpleviz-table
>> [the sales](sales.csv)
>> ["region" "units"]
[:b] #(str %1 "  " (apply str (repeat (quot %1 20) "▇")))
```

| region | units |
|--------|-------|
| North  | =(sum :b 1 2) |
| South  | =(sum :b 3 4) |
| West   | =(sum :b 5 6) |
| East   | =(sum :b 7) |

The second line picks the csv's columns by name, so a is region and b units here. A
json, jsonl or edn file works the same way and always names its fields. The
[guide](https://github.com/sstoehrm/simpleviz/blob/main/docs/guide.md#table-formulas)
has every detail.
