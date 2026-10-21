# Charts

A `simpleviz-chart-table` block right below a table shows the table as a chart. The
button at the top right switches to the table and back. Columns are the table's
letters, and rows its body rows from 1, the same as in its formulas.

| item      | per month |
|-----------|----------:|
| rent      | 950       |
| food      | 420       |
| transport | 85        |
| fun       | 160       |
| total     | =(sum :b 1 4) |

```simpleviz-chart-table
{:kind :bar :x :a :y [:b] :rows [1 4] :title "Spending per month"}
```

## Several series

| quarter | tea | coffee | juice |
|---------|----:|-------:|------:|
| Q1      | 120 | 80     | 40    |
| Q2      | 140 | 95     | 35    |
| Q3      | 110 | 120    | 60    |
| Q4      | 160 | 130    | 45    |

```simpleviz-chart-table
{:kind :bar :x :a :y [:b :c :d] :stack true}
```

Grouped and on its side:

| quarter | tea | coffee |
|---------|----:|-------:|
| Q1      | 120 | 80     |
| Q2      | 140 | 95     |
| Q3      | 110 | 120    |
| Q4      | 160 | -20    |

```simpleviz-chart-table
{:kind :bar :x :a :y [:b :c] :horizontal true}
```

## Lines

A formula's number is charted, never its formatted text.

```simpleviz-table
[:c] #(format "%.0f %%" (* 100.0 %1))
```

| month | visits | growth |
|-------|-------:|-------:|
| Jan   | 1200   |        |
| Feb   | 1350   | =(- (/ (sum :b 2) (sum :b 1)) 1) |
| Mar   | 1280   | =(- (/ (sum :b 3) (sum :b 2)) 1) |
| Apr   | 1720   | =(- (/ (sum :b 4) (sum :b 3)) 1) |
| May   | 2100   | =(- (/ (sum :b 5) (sum :b 4)) 1) |
| Jun   | 2050   | =(- (/ (sum :b 6) (sum :b 5)) 1) |

```simpleviz-chart-table
{:kind :line :x :a :y [:b]}
```

## A chart alone

A `simpleviz-chart` block charts a data file without a table: the first line links
the data, the second names its fields, which become the letters.

```simpleviz-chart
>> [the sales](sales.csv)
>> ["units" "price"]
{:kind :scatter :x :a :y [:b] :title "Price by units sold"}
```
