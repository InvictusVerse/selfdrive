# Self-Driving Car Control System

A Java desktop application that simulates a self-driving electric car with real vehicle physics, driving through a real part of Bengaluru (from OpenStreetMap) with city traffic. It has a Tesla-style driver display and dashboards for Admin, Driver and Maintenance Technician, backed by an embedded database.

## Tech stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| UI and 3D | JavaFX 21 (JavaFX 3D; models generated in code, optional glTF car model) |
| Physics | Custom vehicle dynamics engine (`com.selfdriving.physics`) |
| Map | OpenStreetMap data for Bengaluru, bundled (ODbL) |
| Database | H2 embedded, over JDBC (nothing to install) |
| Build | Maven, via the included wrapper (`mvnw.cmd`) |
| Tests | JUnit 6 (130 tests) |
| Packaging | jpackage: a Windows app folder with its own Java runtime |

## Requirements

- Windows 10/11
- JDK 21 or newer (`java -version`)

Maven does not need to be installed. The wrapper downloads it on first use.

**New PC or sharing with a friend?** Follow [SETUP.md](SETUP.md), then run `scripts\check-environment.cmd -Build`.

## Run

```powershell
.\mvnw.cmd javafx:run
```

**Sign in** with one of the demo accounts, created on first start and listed on the sign-in screen until their passwords are changed:

| Role | Username | Password |
|---|---|---|
| Admin | `admin` | `Admin@2026` |
| Driver | `driver` | `Driver@2026` |
| Maintenance Technician | `tech` | `Tech@2026` |

Change these passwords (rail → **Password**) if the app is used anywhere but a demo.

**Drive off:** hold **S** (brake), press **4** (Drive), then hold **W**. Steer with **A / D** or the arrow keys, and press **Space** for a full-brake test. Press **H** for all keys.

**Touchscreen or mouse:** tap **D**, hold the on-screen **ACCEL** pedal, and drag the steering wheel.

**Lights:** **,** and **.** indicators (they cancel after the turn), **/** hazards, **N** headlights (Off, Auto, On), **K** main beam, hold **J** to flash. Clicking the clock switches between day and night.

**Autopilot:** choose a destination in the Navigation card (the fastest route is planned with A\*), then press **Start autopilot** or **E**. It follows lanes, stops at red lights, gives way, and overtakes slow vehicles. Brake or steer to take over; **X** is an emergency stop. **Y** changes the amount of traffic, **L** shows the lidar points, and the **TEST** buttons put a pedestrian, a stopped car, a slow vehicle or a closed road in the car's path.

**Parking:** below 20 km/h press **Park** or **Q** to park in a free space on the left, along the kerb or in a bay. The proving ground has a car park to try it.

**Car model (optional):** the car is drawn in code. A detailed glTF model can be dropped into `assets/models/car/` instead; see the [README there](assets/models/car/README.md).

In IntelliJ IDEA, open the folder and choose **Run App** from the run dropdown.

## What each role can do

- **Admin**: overview dashboard, users and access levels, system settings, software updates (install and roll back), performance graphs, alert and audit logs, database console, driving and trips
- **Driver**: drive and use the autopilot, trip history, alerts; with full access also the test scenarios
- **Maintenance Technician**: diagnostic scans, issue tracking and fixes, system tests, maintenance history, alerts; with full access also fault injection and driving

## Build, test, package

```powershell
.\mvnw.cmd verify                                                   # build and run all tests
powershell -ExecutionPolicy Bypass -File scripts\windows\package.ps1  # dist\SelfDrivingCarControlSystem\SelfDrivingCarControlSystem.exe
```

The packaged folder includes its own Java runtime, so it runs on a PC with nothing installed. Its data is kept in `%LOCALAPPDATA%\SelfDrive\data`; when run from source, in `data\`.

## How it works

[PROJECT.md](PROJECT.md) is the main project document: requirements, every technology and why, and how each part works in detail (physics, map and lanes, traffic, autopilot, safety, parking, diagnostics, updates, security, database, UI), with the test results.

## Project structure

```text
src/main/java/com/selfdriving/
├── Main.java        entry point
├── app/             JavaFX start-up, developer scripts
├── physics/         vehicle dynamics: tyres, suspension, motor, brakes/ABS, battery
├── vehicle/         VehicleState, gears, drive modes, lights, driver controls
├── world/           proving ground, Bengaluru map, lane network, buildings
├── traffic/         other road users (IDM car following, MOBIL lane changes)
├── navigation/      road graph, A*/Dijkstra, routes, ETA
├── sensors/         simulated lidar, radar, ultrasonic
├── autopilot/       speed and steering control, junctions, overtaking, parking, safety controller (AEB)
├── simulation/      fixed-timestep loop, scenarios, collisions, system tests
├── diagnostics/     fault codes and diagnostic reports
├── alerts/          central alert bus
├── auth/            roles, access levels, permissions, password hashing
├── persistence/     H2 database, migrations, repositories
├── service/         sign-in, users, settings, history, maintenance, updates, recorder
├── tools/           map importer (developer tool)
└── ui/
    ├── login/       sign-in screen
    ├── driver/      Tesla-style driver display
    ├── admin/       overview, users, settings, updates, performance, audit
    ├── technician/  diagnostics, issues, system tests, maintenance log
    ├── common/      shared pages (trips, alerts) and helpers
    └── render/      JavaFX 3D car, city, traffic, glTF loader
src/main/resources/
├── com/selfdriving/ui/theme.css
├── com/selfdriving/world/maps/   bundled city map (OpenStreetMap, ODbL)
└── db/                           SQL migrations
src/test/java/       JUnit tests
scripts/             environment checker, Windows packaging
.run/                shared IntelliJ run configurations
```

Map data © OpenStreetMap contributors, available under the Open Database Licence.
