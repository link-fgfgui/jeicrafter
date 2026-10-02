# JEI Crafter

A pure-client mod that turns JEI bookmarks into a one-click crafting pipeline.

## Features

- **Auto-craft bookmarked recipes** — hold **Z** and click a recipe bookmark to auto-detect
  materials, recursively craft missing bookmarked materials, and complete the crafting.
- **Workstation recipes (non-crafting)** — recipes that need a workstation (smelting, blasting,
  smoking, brewing, …) read **all** of the recipe's catalyst blocks. When the corresponding
  workstation is **already open** (its GUI belongs to one of those blocks) or the open container
  has a **valid JEI recipe transfer handler**, the action inserts the required inputs into the
  workstation and then stops (insert-and-stop: it never waits for the machine to produce and
  never extracts the output), handing control back to the player. Otherwise it opens the
  **nearest matching workstation** block in the loaded world (within the player's interaction
  reach) and then runs the same step — whether the newly opened workstation is auto-filled is
  decided by `autoTransferItems`.
- **Highlight recipe workstations** — on a non-crafting JEI recipe page, press **H** to highlight
  every workstation block for the current recipe across the loaded world.
- **Sort bookmarks** — while hovering over the JEI bookmark overlay, press **F5** to automatically
  reorder bookmarks: identical items are grouped together, recipes with crafting relationships are
  arranged in dependency order (materials before products), recipe bookmarks are treated by their
  displayed item, and unrelated bookmarks preserve their relative positions.

## Configuration

A `jeicrafter.json` config file (in the platform config directory) controls the workstation
features:

| Key | Default | Meaning |
| --- | --- | --- |
| `enableWorkstationHighlight` | `true` | Enable the recipe-workstation highlight (H key). |
| `closeGuiOnWorkstationHighlight` | `true` | Automatically close the open GUI when workstation highlight is triggered. |
| `autoOpenWorkstation` | `true` | When the workstation is not already open (and no valid transfer handler applies), auto-craft opens the nearest matching workstation GUI. |
| `autoTransferItems` | `true` | Whether the workstation is auto-filled after opening (or when it is already open / a valid transfer handler applies). When disabled the action only opens/acknowledges the workstation and stops without inserting anything. |
| `workstationMaxDistance` | `6` | Max distance (blocks) to search for a workstation to open. Defaults to the vanilla server's block interaction limit; farther can never be opened. |
| `minHighlightDistance` | `16` | Reference distance (blocks) for minimum highlight size: highlights will not appear smaller than a 1x1 block viewed at this distance. Beyond it, highlights scale up with distance. Set to 0 to disable. |

## API

Third-party mods can:

- provide actions for their own JEI recipe types
- decide whether a recipe's materials are already available (inventory, storage network, …)
- replace the built-in JEI-bookmark recipe tree with their own planner

See [the bookmark action API](docs/action-api.md).

## License

This project is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0-only)**. See the
[LICENSE](LICENSE) file for details.

## Thanks

The workstation highlight renderer is adapted from
[clientcommands](https://github.com/Earthcomputer/clientcommands) v2.8.2
(Earthcomputer and contributors). Thanks for the reference implementation of block highlighting
across the loaded world.
