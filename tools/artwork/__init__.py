"""The artwork renderers — twenty-four generative designs behind Paperouette's catalogue.

Each module renders resolved RGB/RGBA layers keyed by variant;
`generate_catalog.py` drives them and owns everything catalogue-shaped.

Determinism contract: every random choice flows through `core.rng_for`, seeded by the
stable identifiers in `tools/catalog/designs.toml` and
`tools/catalog/spinner_designs.json`.
Same definitions, same bytes.
"""
