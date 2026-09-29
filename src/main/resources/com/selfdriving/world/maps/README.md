# Map data

`bengaluru-mg-road.json` is an extract of OpenStreetMap around MG Road, Brigade Road and Church
Street in Bengaluru, India (about 1.6 km x 1.2 km): roads with their names, classes, one-way
rules, lane counts and speed limits, traffic signals, building footprints and heights, parks and
named places, converted to metres.

Map data (c) OpenStreetMap contributors, available under the Open Database Licence (ODbL):
https://www.openstreetmap.org/copyright. The app shows this notice on the map.

To make a new extract (the app itself never goes online):

```powershell
java -cp target/classes com.selfdriving.tools.MapImport <south> <west> <north> <east> <output.json> "<name>"
java -cp target/classes com.selfdriving.tools.MapImport 12.9675 77.6000 12.9785 77.6150 src/main/resources/com/selfdriving/world/maps/bengaluru-mg-road.json "Bengaluru, MG Road"
```
