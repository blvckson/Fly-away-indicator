# Indicator round traces

The live monitor now writes a compact CSV feature trace for each confirmed Aviator round.

## Location on Android

Files are stored in the app's external files directory:

`Android/data/com.blvckson.flyawayindicator/files/Documents/FlyAwayIndicatorTraces/`

On Android 8.1, open this folder with a file manager such as ZArchiver. The application does not need broad storage permission for this app-specific directory. If the external app directory is unavailable, traces fall back to the app's internal files directory.

## What is collected

Each CSV contains per-frame timestamps and numeric detector observations:

- Full-frame and central-region visual change.
- Aircraft centroid, bounding-box width/height, pixel count, aspect ratio and fill ratio.
- Relative aircraft geometry and shape changes from the previous observation.
- Both the service's smoothed movement score and the detector's raw frame-to-frame movement score.
- 800 ms and 250 ms time-window trend measurements.
- Current candidate and round baseline scores.
- Whether the pre-warning condition was met, whether the indicator was red, OCR verification state, visual live-screen state and OCR end marker state.
- A final end record explaining whether the trace ended on an OCR round-end marker, sustained live-screen loss or service shutdown.

The trace does not save screenshots, video, audio, account details, bets or balances. It does not upload data anywhere. Up to 40 CSV files are retained; the oldest are removed when a new trace is started.

## How to share a trace

1. Run the indicator while watching real Aviator rounds.
2. Allow each round to finish so its CSV is closed.
3. Open the folder above in ZArchiver.
4. Share the relevant `round-*.csv` files for offline analysis.

Keep both rounds that generated a red warning and rounds that ended without a warning. The latter are needed to distinguish real pre-fly-away behaviour from ordinary animation changes.

## Important limitation

The feature trace is measurement instrumentation, not a prediction guarantee. Geometry and shape changes are recorded for comparison but are not yet independent red-alert triggers. Their thresholds should only be calibrated after comparing labelled pre-fly-away and ordinary-flight examples.
