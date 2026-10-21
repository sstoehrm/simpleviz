# Charts

A chart in an md file is a fenced block with a small EDN map. It charts
a table in the same file, or a data file on its own:

    | item      | per month |
    |-----------|----------:|
    | rent      | 950       |
    | food      | 420       |
    | transport | 85        |
    | total     | =(sum :b 1 3) |

    ```simpleviz-chart-table
    {:kind :bar :x :a :y [:b] :rows [1 3] :title "Per month"}
    ```

- A ```` ```simpleviz-chart-table ```` block right below a table shows
  that table as a chart. A button at its top right switches to the table
  and back. Columns are the table's letters, `:a` the first, and rows
  are its body rows from 1, as in its formulas. A table takes one chart
  block; to chart it twice, repeat the table.
- The chart takes a formula's number, never its formatted text, so `=(/
  1 4)` shown as `25 %` is charted as 0.25. It charts the table, never
  the data file the table's own `simpleviz-table` block may link.
- On GitHub and in any editor, the table stays a table and the block
  shows as a short code block.

## A chart alone

A ```` ```simpleviz-chart ```` block charts a data file without a table.
Its first two lines are those of a
[table block](tables.md#over-a-data-file): the data, then its fields,
which become the letters:

    ```simpleviz-chart
    >> [the sales](sales.csv)
    >> ["units" "price"]
    {:kind :scatter :x :a :y [:b] :title "Price by units sold"}
    ```

The fields line is required here, since no table shows the columns.
Each record is a row, in the file's order. The chart doesn't group or
sum: to chart totals per region, sum them in a table and chart that
table.

## The map

| key | value |
|-----|-------|
| `:kind` | `:bar`, `:line` or `:scatter` (required) |
| `:x` | the column of the categories (bar) or x values (line, scatter) |
| `:y` | one column or a vector of them, a series each |
| `:rows` | `[from to]`, the rows to chart, for example to leave out a totals row |
| `:stack` | `true` stacks the bars |
| `:horizontal` | `true` lays the bars on their side |
| `:title` | the title; a chart of one series is titled by its column |

Two or more series get a legend. A line or scatter whose x values are
all numbers gets a numeric x axis; otherwise the x values are evenly
spaced categories. A blank cell is a gap: no bar, no dot, and a break
in the line. Hover a mark for its value.

`simpleviz demo` brings `charts.md`, which has one chart of each kind.

## Checking

`simpleviz check notes.md` reports a chart block that isn't right below
a table, a map that doesn't read, a column or row the table or fields
lack, and data that can't be read. Like a table's formulas, the
formulas a chart charts are not run: serve the file and look.

## More

- [Tables](tables.md): formulas and data files.
- [Charts](guide.md#charts) in the guide: every rule.
