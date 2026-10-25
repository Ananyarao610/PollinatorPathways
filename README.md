# Pollen Pathways 

A small native Android app for collecting GPS fixes during a walk and exporting them to CSV.

## What the app does
- Shows a live camera preview.
- Starts a park survey with GPS logging and video recording.
- Stops the video before processing it.
- Extracts one still frame approximately every 5 seconds (first frame at 0.5 seconds).
- Matches each frame to the nearest GPS fix by timestamp and records the GPS time difference.
- Exports the GPS log CSV and frame metadata CSV.
- Exports a ZIP containing the survey video, extracted JPG frames, and metadata CSVs.
- Stores survey files in app-specific storage under `Movies/PollenPathways/survey_<timestamp>/`.

## Data outputs
- `gps_readings.csv`: sample ID, timestamp, latitude, longitude, accuracy and altitude.
- `frame_metadata.csv`: frame ID, timestamp, video offset, matched latitude/longitude, accuracy, GPS time difference and image filename.
- `survey_<timestamp>.mp4`: original recorded video.
- `frames/frame_*.jpg`: extracted still frames.

