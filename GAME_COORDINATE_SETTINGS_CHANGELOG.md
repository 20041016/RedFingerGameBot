# Game-mode coordinate settings

- Added an independent `game_coordinates` preference namespace; music-mode coordinates remain in `music_coordinates`.
- Added one-shot Root touch recording for game-mode reference coordinates (1280x720 landscape): run button X/Y, extra click X/Y, search swipe start X/Y, and search swipe end X/Y.
- Added editable dead-zone and view-correction gain controls. Gain is stored in thousandths to avoid floating-point preference drift.
- `GameBot` reads the saved game-mode values at engine initialization. OCR-derived dynamic dialog/jump targets remain OCR-derived and are not replaced by fixed coordinates.
- Default values remain: run (1066,194), extra click (1231,503), search swipe (960,360)->(660,360), dead zone 50, gain 0.30.
- Root touch recording is disabled while either engine is running, preventing the injected game touches from being captured as user calibration input.

## Paired XY capture correction
- Game-mode coordinate rows now represent complete points: run button, extra button, search swipe start, and search swipe end.
- Each row has one `记录 X/Y` action. A single Root touch event is converted once into landscape 1280x720 reference coordinates and updates both axes atomically.
- The music-mode coordinate capture path is unchanged. Game-mode X/Y values remain stored under the independent `game_coordinates` preference store.
