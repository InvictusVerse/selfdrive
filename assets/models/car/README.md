# Car model (optional)

The app draws its own car in code. To show a detailed model instead, put one binary glTF file
here: `model.glb`, or a `.zip` that contains one. The first file by name is used. Model files in
this folder are ignored by Git, so check the model's licence before sharing it anywhere.

What the loader needs:

- wheel nodes named like `tire FL`, `tire FR`, `tire RL`, `tire RR` (also `tyre` or `wheel`), used to
  find the axles, orientation and scale;
- optional lamp pairs `X` / `X Lights_On` (headlights, tail lights, brake light) and
  `X` / `X Turn_Signal` (indicators), where the lit part is hidden with a zero scale.

A different file or folder can be chosen with `-Dselfdrive.carModel=<path>`.
