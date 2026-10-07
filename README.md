# Fly Away Indicator

Android visual-behaviour research/indicator for Aviator live rounds.

Core design:
- Detect the live Aviator screen before recording.
- Record each complete live round and stop/pause when the live screen disappears.
- Analyse recorded frames at high temporal density / slow-motion-equivalent timing.
- Compare earlier stages to the pre-fly-away stage by **difference**.
- Compare pre-fly-away stages across rounds by **similarity**.
- Learn recurring pre-fly-away visual/behavioural characteristics.
- Examine plane movement, direction, position, size, colour and attachment; multiplier appearance/colour/size/font and behaviour; live-area graphics, light and colour; and meaningful visual transitions.
- Extract the red end-of-round multiplier from each recorded round.
- Preserve the draggable indicator/overlay concept and illuminate a red indicator when pre-fly-away evidence is detected.
- Do not automate betting, wagering or cash-out.

The implementation is designed to reuse the established Aviator-Behaviour-AI analysis concepts while adding complete-round video and temporal analysis.
