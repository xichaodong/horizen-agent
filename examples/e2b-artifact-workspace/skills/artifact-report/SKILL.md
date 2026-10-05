---
name: artifact-report
description: Generate a deterministic JSON summary from the bundled public sample order data.
---

# Artifact report

Use the existing script instead of rewriting its calculation logic.

1. Run `scripts/build_report.py` with `references/orders.json` as the input.
2. Write the result to `outputs/artifact-report.json` in the workspace.
3. Deliver that file with the `deliver_artifact` tool.

Example:

```sh
python3 <files-root>/scripts/build_report.py \
  --input <files-root>/references/orders.json \
  --output outputs/artifact-report.json
```
