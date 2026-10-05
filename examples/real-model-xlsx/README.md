# Synthetic XLSX fixture

`source-sales.xlsx` contains a single synthetic sales table: Notebook (4 × 25), Monitor (2 × 180), Keyboard (5 × 40), and a formula-based total of 660. Product names and amounts are demonstration values; there are no customer, merchant, account or contact identifiers.

`RealModelXlsxTaskLiveTest` uses this file to exercise upload, sandbox modification, artifact delivery and history recovery. It requires explicit `-Dhorizen.real.xlsx.live=true` opt-in and separately configured model, MySQL/Redis, BOS and E2B services; see the [configuration template](../../.env.yml.example). Ordinary verification skips it. Do not replace this fixture with production data.
