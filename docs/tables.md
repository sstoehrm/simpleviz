# Tables

Tables are GitHub tables in an md file, and they can calculate. A cell
starting with `=(` is a formula, plain Clojure; the page shows its
result, and the formula when you hover the cell. A
```` ```simpleviz-table ```` block right above the table formats its
cells, and the page hides it:

    ```simpleviz-table
    [:c] #(str %1 " €")
    [:d] #(str (* %1 100) "%")
    [:d 3] (fn [v ctx] {:color "green"})
    ```

    | item | qty | price | share |
    |------|----:|------:|------:|
    | tea  | 2   | 3.5   | 0.1   |
    | milk | 1   | 1.2   | 0.25  |
    | sum  | =(sum :b) | =(sum :c) | =(sum :d 1 2) |

- Columns are letters, rows the body rows from 1: `(sum :b 1 2)` adds
  rows 1 and 2 of column b, `(sum :b)` the whole column. `sum`, `avg`,
  `count`, `min`, `max` and `table` take those ranges.
- Each pair in the block is a range and a fn. A string or number it
  returns is what the cell shows; a map colours the cell.
- Formulas run in a sandbox, in a process of their own, with a second
  each.
- In Edit, Format aligns the columns, so the plain text reads as a
  table too.

## Over a data file

A block may start with a link to a `.csv`, `.jsonl`, `.json` or `.edn`
file. The table's formulas then work on its records instead: columns
are its fields in order, rows its records, and `(rows)` is every record
as a map.

    ```simpleviz-table
    >> [the sales](sales.csv)
    ```

    | what       | value |
    |------------|------:|
    | records    | =(count (rows)) |
    | units sold | =(sum :c) |

`simpleviz demo` brings `tables.md`, which sums, formats and colours
its tables, one of them over `sales.csv`.

## More

- [Table formulas](guide.md#table-formulas) — every rule.
- [Editing docs](guide.md#editing-docs) — what Format changes.
