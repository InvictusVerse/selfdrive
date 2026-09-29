# Self-Driving Car Control System

A Java desktop application that simulates a self-driving electric car with real vehicle physics. It has a Tesla-style driver display and dashboards for Admin, Driver and Maintenance Technician.

## Tech stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| UI and 3D | JavaFX 21 (JavaFX 3D, procedural models, no Blender) |
| Physics | Custom vehicle dynamics engine (`com.selfdriving.physics`) |
| Database | H2 embedded, over JDBC (nothing to install) |
| Build | Maven, via the included wrapper (`mvnw.cmd`) |
| Tests | JUnit 6 |

## Requirements

- Windows 10/11
- JDK 21 or newer (`java -version`)

Maven does not need to be installed. The wrapper downloads it on first use.

**New PC or sharing with a friend?** Follow [SETUP.md](SETUP.md), then run `scripts\check-environment.cmd -Build`.

## Run

```powershell
.\mvnw.cmd javafx:run
```

**Drive off:** hold **S** (brake), press **4** (Drive), then hold **W**. Steer with **A / D** or the arrow keys, and press **Space** for a full-brake test. Press **H** for all keys.

**Touchscreen or mouse:** tap **D**, hold the on-screen **ACCEL** pedal, and drag the steering wheel.

**Autopilot:** choose a destination in the Navigation card (the fastest route is planned with A\*), then press **Start autopilot** or **E**. Brake or steer to take over, and press **X** for an emergency stop. The **Pedestrian**, **Stopped car** and **Road closed** buttons put test situations in the car's path. Press **L** to show the lidar points.

In IntelliJ IDEA, open the folder and choose **Run App** from the run dropdown. The shared configurations in `.run/` load automatically.

## Build and test

```powershell
.\mvnw.cmd verify
```

## How it works

[PROJECT.md](PROJECT.md) is the main project document. It covers what the system does, every technology it uses and why, and how each part works in detail (physics, autopilot, sensors, navigation, database, UI).

## Project structure

```text
src/main/java/com/selfdriving/
├── Main.java        entry point
├── app/             JavaFX bootstrap, application context, session
├── config/          admin-editable settings
├── physics/         vehicle dynamics: tyres, suspension, motor, brakes/ABS, battery
├── vehicle/         VehicleState, gears, drive modes, lights, vehicle controller
├── world/           city, roads, obstacles, pedestrians, traffic, road conditions
├── navigation/      road graph, A*/Dijkstra, route optimisation, ETA
├── sensors/         simulated lidar, radar, ultrasonic
├── autopilot/       speed + steering control, safety controller (AEB)
├── simulation/      fixed-timestep loop, demo scenarios
├── diagnostics/     fault codes, diagnostics, fixes, system tests, issues
├── alerts/          central alert bus
├── updates/         OTA software updates
├── auth/            users, roles, access levels, password hashing
├── persistence/     H2 database, migrations, repositories
└── ui/
    ├── login/       login screen
    ├── driver/      Tesla-style driver display
    ├── admin/       admin dashboard
    ├── technician/  technician dashboard
    └── render/      JavaFX 3D car, city, sensor and debug visualisation
src/main/resources/
├── com/selfdriving/ui/theme.css
└── db/              SQL schema and seed data
src/test/java/       JUnit tests
scripts/             environment checker for new PCs
.run/                shared IntelliJ run configurations
```

## User roles

- **Admin**: user management, system settings, software updates, performance metrics, alerts
- **Driver**: set destination (route + ETA), monitor car status, override autopilot, navigation history, alerts
- **Maintenance Technician**: diagnose issues, apply fixes, run system tests, issue tracking, maintenance history, alerts
