# Self-Driving Car Control System: Project Guide

> A complete, documented reference implementation of a self-driving car control system · Java desktop application for Windows
>
> This is the main reference for the whole project: what it is, what it uses, and how every part works.
> It is a living document. Each section is marked **✅ Built** or **📐 Designed (not built yet)** and is updated at the end of every stage.

---

## Contents

1. [What the project is](#1-what-the-project-is)
2. [Requirements](#2-requirements)
3. [Technology and why](#3-technology-and-why)
4. [Architecture](#4-architecture)
5. [How it works](#5-how-it-works)
   - [5.1 Simulation loop and threads](#51-simulation-loop-and-threads)
   - [5.2 Vehicle physics](#52-vehicle-physics)
   - [5.3 Sensors](#53-sensors)
   - [5.4 Navigation and route optimisation](#54-navigation-and-route-optimisation)
   - [5.5 Autopilot](#55-autopilot)
   - [5.6 Safety controller](#56-safety-controller)
   - [5.7 Drive modes and override](#57-drive-modes-and-override)
   - [5.8 Diagnostics, faults and system tests](#58-diagnostics-faults-and-system-tests)
   - [5.9 Alerts](#59-alerts)
   - [5.10 Software updates (OTA)](#510-software-updates-ota)
   - [5.11 Users, roles and security](#511-users-roles-and-security)
   - [5.12 Database](#512-database)
   - [5.13 User interface and 3D](#513-user-interface-and-3d)
6. [Build, run, test, package](#6-build-run-test-package)
7. [Project structure](#7-project-structure)
8. [Status](#8-status)
9. [Glossary](#9-glossary)

---

## 1. What the project is

A **software simulation** of the control system inside a self-driving electric car, styled like a Tesla. It runs as one Java program on a Windows PC and needs no car hardware, internet or database server.

The car in the simulation is moved by **real physics**: motor torque, tyre grip, weight, suspension, air drag and braking. It does not follow a pre-drawn line. On top of the physics sits a control stack like a real autonomous car's: sensors detect the world, navigation plans a route, the autopilot drives it, and a safety controller can overrule everything.

Three kinds of user log in and each gets their own dashboard:

| Role | What they do |
|---|---|
| **Admin** | Manages users and access levels, system settings, and software updates. Watches performance metrics and alerts |
| **Driver** | Sets a destination and gets a route with ETA, monitors the car live, and can override the autopilot |
| **Maintenance Technician** | Runs diagnostics, fixes faults, runs system tests, and tracks issues and maintenance history |

**Out of scope:** real vehicle hardware (CAN bus, cameras, GPS), machine-learning perception, and production safety certification.

---

## 2. Requirements

Core goals: **navigation, obstacle detection, route optimisation**, with administrators managing system settings, user access and software updates.

| # | Requirement | Where it is implemented |
|---|---|---|
| A1 | Admin: user management (name, role, access level → confirmation) | `auth`, `ui.admin` |
| A2 | Admin: system settings (configuration → confirmation) | `config`, `ui.admin` |
| A3 | Admin: software updates (package → update confirmation) | `updates`, `ui.admin` |
| A4 | Admin dashboard: users table, settings, update status, performance graphs, alerts | `ui.admin` |
| D1 | Driver: set destination (address → route + ETA) | `navigation`, `ui.driver` |
| D2 | Driver: monitor car status (request → real-time status) | `vehicle`, `ui.driver` |
| D3 | Driver: override autonomous control (command → control status) | `vehicle`, `autopilot`, `ui.driver` |
| D4 | Driver dashboard: route management, car status, override panel, navigation history, alerts | `ui.driver` |
| T1 | Technician: diagnose issues (command → diagnostic report) | `diagnostics`, `ui.technician` |
| T2 | Technician: fix issues (command → fix confirmation) | `diagnostics`, `ui.technician` |
| T3 | Technician: run system tests (command → test results) | `diagnostics`, `ui.technician` |
| T4 | Technician dashboard: diagnostic tools, issue tracking, tests, maintenance history, alerts | `ui.technician` |
| S1 | Obstacle detection | `sensors`, `autopilot` |
| S2 | Route optimisation | `navigation` |

---

## 3. Technology and why

| Area | Technology | Version | Why this one |
|---|---|---|---|
| Language | **Java** | 21 LTS | Mature, strongly typed, fast; long-term support release |
| UI | **JavaFX** | 21.0.12 | Modern Java desktop UI with CSS styling, charts, tables and animation |
| 3D | **JavaFX 3D** | (part of JavaFX) | Same toolkit as the UI, so the 3D view sits inside the dashboard. Meshes are generated in code |
| Physics | **Own engine** | — | Every equation is in our code and can be explained and tested |
| Database | **H2** (embedded) | 2.5.252 | Full SQL database that runs inside the app; nothing to install ([details](#512-database)) |
| DB access | **JDBC** | (part of Java) | Standard Java database API; plain SQL with `PreparedStatement` |
| Password hashing | **PBKDF2-HMAC-SHA256** | (part of Java) | Built into the JDK; no extra library |
| Build | **Maven** via wrapper | 3.9.16 | Standard Java build tool; the wrapper means nobody installs Maven |
| Tests | **JUnit** | 6.1.3 | Standard Java test framework |
| Packaging | **jpackage** | (part of JDK) | Produces a Windows app folder with `.exe` and bundled Java |
| Version control | **Git + GitHub** (private) | — | History, backup, sharing with teammates |

**Deliberately not used:** Spring (no server needed), game engines such as jMonkeyEngine or LibGDX (one toolkit is simpler), physics libraries (we want to explain our own), Blender models, online maps.

---

## 4. Architecture

The code is layered. Each layer only talks to the layer below it.

```text
┌─────────────────────────────────────────────────────────────────────┐
│  PRESENTATION  ui.login · ui.driver · ui.admin · ui.technician      │
│                ui.render (3D)                                       │
├─────────────────────────────────────────────────────────────────────┤
│  SERVICES      auth · config · updates · diagnostics · alerts       │
├─────────────────────────────────────────────────────────────────────┤
│  CONTROL       navigation → autopilot → safety controller           │
│                sensors ──────────────────┘                          │
├─────────────────────────────────────────────────────────────────────┤
│  SIMULATION    simulation loop · physics · world                    │
├─────────────────────────────────────────────────────────────────────┤
│  STATE         VehicleState (single source of truth)                │
├─────────────────────────────────────────────────────────────────────┤
│  PERSISTENCE   persistence (repositories) → JDBC → H2 file          │
└─────────────────────────────────────────────────────────────────────┘
```

**Design rules**

1. **`VehicleState` is the single source of truth.** Physics writes it. The UI, 3D view, diagnostics and database only read snapshots of it.
2. **Safety always wins.** The autopilot and the driver only *request* throttle, brake and steering. The safety controller decides what reaches the physics.
3. **The 3D view never changes the simulation.** It only draws what `VehicleState` says.
4. **No slow work on the UI or simulation thread.** Database writes happen on a background worker.
5. **Permissions are checked in services**, not just by hiding buttons.

**Patterns used:** layered architecture, MVC for screens, Observer (alert bus, state listeners), Strategy (path finders, tyre models), State (drive modes, update lifecycle), Repository (database access), Command (override and diagnostic commands).

---

## 5. How it works

### 5.1 Simulation loop and threads

📐 *Designed (Stage 1)*

| Thread | Rate | Job |
|---|---|---|
| **Simulation thread** | 120 Hz fixed (Δt = 1/120 s ≈ 8.3 ms) | Update world → sensors → navigation → autopilot → safety → physics → publish snapshot |
| **JavaFX UI thread** | Screen refresh (~60 fps) via `AnimationTimer` | Read the latest snapshot, update the 3D scene and gauges |
| **Database worker** | On demand | Save trips, alerts, faults and logs without blocking |

A **fixed timestep** makes the physics stable and repeatable: the same inputs always produce the same result, whatever the frame rate. The loop uses an *accumulator*: real elapsed time is added up and consumed in exact 1/120 s steps.

The simulation publishes an **immutable snapshot** of `VehicleState` after each step. The UI reads the newest one, so the two threads never read and write the same object at the same time.

Simulation speed (×0.25 to ×4) and pause are Admin settings. Pause and slow motion are handy for presentations and for studying the physics moment by moment.

### 5.2 Vehicle physics

📐 *Designed (Stage 1)*

The car is a **rigid body** moving on a flat plane (x, y, heading ψ), with four wheels, each with its own tyre, suspension and brake. Forces are summed every step and integrated with **semi-implicit Euler** (velocity first, then position), which is simple and stable at 120 Hz.

**Starting parameters** (similar to a mid-size electric sedan, tuned in Stage 1):

| Parameter | Value |
|---|---|
| Mass *m* | 1 850 kg |
| Wheelbase *L* | 2.875 m |
| Track width | 1.58 m |
| Centre-of-gravity height *h* | 0.45 m |
| Wheel radius *R* | 0.34 m |
| Drag coefficient *C<sub>d</sub>* · frontal area *A* | 0.23 · 2.22 m² |
| Rolling resistance *C<sub>rr</sub>* | 0.010 |
| Battery | 75 kWh |
| Motor | 300 kW peak, 420 N·m |
| Max steering angle | ±35° |

**Forces modelled**

| Effect | Model |
|---|---|
| **Tyre grip** | Pacejka *magic formula*: *F = D·sin(C·atan(B·x − E·(B·x − atan(B·x))))*, where *x* is slip, *D = μ·F<sub>z</sub>* is peak force |
| Lateral slip (cornering) | Slip angle *α = atan((v<sub>y</sub> + a·r) / v<sub>x</sub>) − δ* (front; rear uses −b) |
| Longitudinal slip (accel/brake) | Slip ratio *κ = (ω·R − v<sub>x</sub>) / \|v<sub>x</sub>\|* |
| Combined slip | Friction ellipse: total tyre force cannot exceed *μ·F<sub>z</sub>*, so braking in a corner reduces grip |
| **Weight transfer** | Per-wheel spring-damper *F = k·x + c·ẋ*; braking pitches the car forward and loads the front tyres, cornering loads the outer tyres |
| **Electric motor** | Constant torque up to base speed, then constant power: *T = min(T<sub>max</sub>, P<sub>max</sub> / ω)* |
| **Regenerative braking** | Lifting off the accelerator makes the motor brake the car and recharge the battery |
| **Friction brakes** | Brake torque per wheel with front/rear bias |
| **ABS** | If a wheel's slip ratio goes below about −0.15 (locking), brake pressure is released and re-applied |
| **Traction control** | If a driven wheel spins (slip ratio > about 0.15), motor torque is cut |
| **Air drag** | *F = ½·ρ·C<sub>d</sub>·A·v²* with air density ρ = 1.225 kg/m³ |
| **Rolling resistance** | *F = C<sub>rr</sub>·m·g* |
| **Battery** | Electrical power = mechanical power ÷ efficiency; state of charge and remaining range update continuously |

**Road surfaces** (Admin setting or demo toggle) change the friction coefficient μ:

| Surface | μ (approx.) |
|---|---|
| Dry asphalt | 1.0 |
| Wet asphalt | 0.7 |
| Snow | 0.3 |
| Ice | 0.1 |

**How we prove it is real physics:** results are compared with textbook formulas in JUnit tests, and the same checks can be reproduced live in the app. For example, braking distance from 100 km/h on a dry road is *d = v² / (2·μ·g)* = 27.8² / (2 × 1.0 × 9.81) ≈ **39 m**. Other checks: top speed where drag equals motor force, 0–100 km/h time, and battery energy used over a trip.

### 5.3 Sensors

📐 *Designed (Stage 2)*

Sensors are simulated by **ray casting**: straight lines are shot out from the car and tested against the shapes of obstacles in the world.

| Sensor | Coverage | Returns | Used for |
|---|---|---|---|
| **Lidar** | 360°, ~100 m, many rays per sweep | Distance per ray (point cloud) | Obstacle detection, 3D visualisation |
| **Front radar** | ~160 m, narrow cone ahead | Distance + closing speed of the nearest object | Adaptive cruise, emergency braking |
| **Ultrasonic** (×8) | ~8 m around the car | Near distance | Low-speed manoeuvres, parking |

Readings get small random noise so they are not unrealistically perfect. A sensor can be put into a **fault** state (dead, noisy, stuck). The autopilot then has less information, the fault raises an alert, and the technician can diagnose and fix it.

### 5.4 Navigation and route optimisation

📐 *Designed (Stage 2)*

The city is a **road graph**. Intersections are nodes, and roads are edges with length, speed limit, lane count, surface and a *blocked* flag. The map, 3D city and routing all come from this one graph.

- **Path finding:** **A\*** finds the fastest route. The edge cost is travel time (length ÷ expected speed) plus penalties for blocked or slow roads. The heuristic is straight-line distance ÷ the highest speed limit, so it never overestimates and A\* stays optimal. **Dijkstra** is also included to compare against A\* in tests.
- **Destination input:** the driver picks a named place (e.g. "Tech Park", "Central Station") or clicks the map. The nearest road point becomes the goal.
- **ETA:** the sum over route edges of *length ÷ expected speed*, recalculated live from the car's actual progress.
- **Re-routing:** if a road on the route becomes blocked (demo scenario), A\* runs again from the car's position and the driver sees the new route and ETA.

### 5.5 Autopilot

📐 *Designed (Stage 2)*

The autopilot converts the route into throttle, brake and steering **requests**:

1. **Target speed** = the lowest of: road speed limit, Admin's max autopilot speed, safe cornering speed *v = √(a<sub>lat,max</sub> / curvature)*, and the speed that keeps a safe gap to the car ahead.
2. **Speed control:** a **PID controller** turns *target − actual speed* into accelerator or brake.
3. **Steering control:** **pure pursuit** picks a point on the lane centre a look-ahead distance ahead (longer at higher speed) and steers towards it: *δ = atan(2·L·sin α / L<sub>d</sub>)*.
4. **Behaviour:** follow lane, slow for curves, stop at red lights and for pedestrians, stop at the destination.

### 5.6 Safety controller

📐 *Designed (Stage 2)*

Sits between the autopilot or driver and the physics, and can overrule both.

| Check | Rule | Action |
|---|---|---|
| Forward collision warning | Time-to-collision *TTC = distance ÷ closing speed* < 2.5 s | Warning alert + sound |
| **Automatic emergency braking** | TTC < ~1.2 s, **or** distance < stopping distance *v²/(2μg) + v·t<sub>reaction</sub>* + margin | Full braking (ABS active), critical alert |
| Sensor failure | Required sensor faulted | Limit speed or refuse autopilot |
| Speed limit | Above max allowed | Cap throttle |

**Priority order:** Emergency stop → Emergency braking → Driver override → Autopilot.

### 5.7 Drive modes and override

📐 *Designed (Stages 1–2)*

```text
      ┌────────────┐  driver takes over   ┌──────────────────┐
      │ AUTONOMOUS │ ───────────────────▶ │ MANUAL OVERRIDE  │
      │            │ ◀─────────────────── │                  │
      └─────┬──────┘  driver re-engages   └────────┬─────────┘
            │                                      │
            └───────────────┐      ┌───────────────┘
                            ▼      ▼
                       ┌────────────────┐
                       │ EMERGENCY STOP │ ── reset when stopped ──▶ MANUAL
                       └────────────────┘
```

- **Gears:** P, R, N, D. Changing between D and R needs the car (almost) stopped and the brake pressed, like a real car.
- **Manual driving:** keyboard (W/S or ↑/↓ accelerate and brake, A/D or ←/→ steer), with smooth input ramps so keys feel like pedals.
- **Taking over:** touching brake or steering, or pressing the override button, instantly switches to manual. The driver dashboard always shows the current mode.

### 5.8 Diagnostics, faults and system tests

📐 *Designed (Stage 4)*

- **Fault codes** come from the simulated car itself, e.g. `SNS-RAD-01` front radar no signal, `MTR-TEMP-02` motor over-temperature, `TYR-PRS-FL` front-left low tyre pressure, `BRK-ABS-03` ABS valve fault.
- **Fault injection:** a demo panel can trigger any fault on purpose.
- **Diagnose:** checks each subsystem (sensors, motor, battery, brakes, steering, tyres, navigation, database) and produces a report with status, readings and fault codes.
- **Fix:** clears the fault in the simulation (e.g. recalibrate the radar, reset the inverter). The effect is visible: the sensor comes back and the alert clears.
- **System tests:** automated checks with pass or fail and a duration, e.g. brake response, steering range, sensor self-test, ABS cycle, battery health, route calculation.
- **Issue tracking:** every fault becomes an issue, `OPEN → IN_PROGRESS → FIXED → VERIFIED → CLOSED`, with technician, action and times stored as **maintenance history**.

### 5.9 Alerts

📐 *Designed (Stage 2)*

One central **alert bus** (Observer pattern). Any part of the system publishes an alert, and each dashboard subscribes to the categories its role cares about.

- **Severity:** `INFO`, `WARNING`, `CRITICAL`. Critical alerts stay on screen until acknowledged.
- **Categories:** obstacle, collision risk, sensor, brakes, motor, battery, navigation, software update, maintenance, security (e.g. failed logins).
- Every alert is saved to the database with who acknowledged it and when.

### 5.10 Software updates (OTA)

📐 *Designed (Stage 4)*

Simulates how a Tesla receives over-the-air updates. The Admin selects an update package (version + release notes) and deploys it. It moves through `AVAILABLE → DOWNLOADING → INSTALLING → COMPLETED` (or `FAILED → ROLLED_BACK`) with a progress bar. Installation is blocked while the car is driving. Updates can change real settings (e.g. a new max speed or improved emergency-braking threshold), so the effect is visible. History is stored in the database.

### 5.11 Users, roles and security

📐 *Designed (Stage 3)*

- **Roles:** `ADMIN`, `DRIVER`, `TECHNICIAN`. **Access levels** within a role (e.g. `STANDARD`, `FULL`) control extra permissions.
- **Passwords** are never stored as plain text. Each password gets a random 16-byte salt and is hashed with **PBKDF2-HMAC-SHA256** (600 000 iterations, the OWASP recommendation). Login compares hashes in constant time.
- **Login protection:** after 5 wrong attempts, the account is locked for a short time and a security alert is raised.
- **Checks in the service layer:** every action (e.g. delete user) verifies the logged-in user's role, even if the UI already hid the button.
- **SQL injection:** all queries use `PreparedStatement` parameters. User input is never concatenated into SQL.
- **Demo accounts** are created on first start. They are for demonstration only, and their passwords should be changed if the app is used anywhere else.

### 5.12 Database

✅ *Chosen* · 📐 *Schema designed (Stage 3)*

**Choice: H2, embedded mode.** H2 is a full relational SQL database written in Java. In embedded mode it runs *inside* our program and stores everything in one file (`data/selfdriving.mv.db`), so there is no server to install, start or configure.

| Option | Verdict for this project |
|---|---|
| **H2 (chosen)** | ✅ Pure Java, embedded, full SQL with transactions (ACID), single file, built-in web console for showing tables, MySQL compatibility mode |
| SQLite | 👍 Also embedded and very widely used, but uses a native library, has weaker type checking and limited `ALTER TABLE`. A good second choice |
| MySQL / PostgreSQL | 👎 Excellent server databases, but every PC running the app would need a server installed, running and configured. That adds setup work and failure points with no benefit for a single-user desktop app |

**Inspecting the database:** H2 ships with a web console (opened from the Admin dashboard in Stage 3) where tables can be browsed and SQL run live. The file can also be opened in DBeaver.

**If MySQL is ever required:** the code uses standard SQL through JDBC, so switching means changing the JDBC URL and driver dependency. H2's `MODE=MySQL` keeps the SQL compatible in the meantime.

**Planned tables**

| Table | Holds |
|---|---|
| `users` | id, username, full name, password hash, salt, role, access level, active, failed attempts, created/updated |
| `settings` | key, value, type, updated by, updated at |
| `software_updates` | id, version, notes, status, progress, started/finished, deployed by |
| `trips` | id, driver, origin, destination, distance, ETA, actual time, energy used, status, start/end |
| `alerts` | id, severity, category, message, source, created at, acknowledged by/at |
| `issues` | id, fault code, subsystem, severity, description, status, detected at, assigned technician, resolution, resolved at |
| `maintenance_log` | id, technician, issue, action, result, timestamp |
| `test_runs` / `test_results` | run id, technician, time; per test: name, status, duration, message |
| `audit_log` | who did what and when (logins, user changes, settings changes) |

The schema is created by versioned SQL scripts in `src/main/resources/db/` that run automatically at start-up.

### 5.13 User interface and 3D

📐 *Designed (Stages 1–4)* · ✅ *Theme and window shell built*

- **Look:** dark Tesla-style theme (`theme.css`): near-black background, soft grey panels, one accent blue, green, amber and red for status, large touch-friendly controls, smooth transitions.
- **Login:** username + password, then routed to the right dashboard for the role.
- **Driver display** (like the Tesla centre screen):
  - *Left:* 3D Autopilot view: our car from behind, lane lines, detected cars and pedestrians as simple shapes, the planned path.
  - *Right:* top-down city map with the route, ETA and turn list.
  - *Top bar:* speed, gear, battery %, range, drive mode.
  - *Bottom:* override, emergency stop, lights, road surface, alerts.
- **3D models are generated in code:** the car body is a custom `TriangleMesh` shaped like a modern sedan, plus wheels that spin and steer, brake lights that turn on when braking, and headlights. Buildings, roads and obstacles are generated from the road graph. No Blender or model files.
- **Physics debug overlay** (toggle): tyre force arrows, slip values, live graphs of speed and forces. This is the visual proof that the physics is real.
- **Cameras:** chase, top-down, driver view, free orbit.
- **Accessibility:** every control has a keyboard shortcut and focus outline, text meets contrast guidelines on the dark theme, and status is never shown by colour alone (icons + text too).

---

## 6. Build, run, test, package

| Task | Command | IntelliJ |
|---|---|---|
| Run the app | `.\mvnw.cmd javafx:run` | **Run App** |
| Build + all tests | `.\mvnw.cmd verify` | **Build and Test** |
| Debug | — | **Run Main (direct)** with 🐞 |
| Check a new PC | `scripts\check-environment.cmd -Build` | — |
| Package for distribution | *(Stage 4)* `jpackage` → app folder with `SelfDrivingCarControlSystem.exe` + bundled Java | — |

**Build safeguards:** compiler warnings are enabled (`-Xlint:all`), and the Maven Enforcer plugin rejects Java older than 21 or Maven older than 3.9 with a readable message.

**Tests** (added with each stage): physics against textbook formulas (braking distance, top speed, energy use), A\* vs Dijkstra on the same graph, PID and pure pursuit behaviour, safety-controller rules (brakes when TTC is low), permissions (a driver cannot delete users), and repository round-trips with an in-memory H2 database.

**Packaging note:** the release build will be a self-contained app folder with an `.exe` launcher and its own Java runtime, so the target PC needs nothing installed. A classic Windows *installer* (`.msi`/setup `.exe`) would also need the free WiX Toolset on the build PC. That is optional.

New PC setup: see [SETUP.md](SETUP.md).

---

## 7. Project structure

```text
selfdriving/
├── PROJECT.md                   this document
├── README.md                    short overview
├── SETUP.md                     setting up another PC
├── pom.xml                      build, dependencies, pinned versions
├── mvnw, mvnw.cmd, .mvn/        Maven wrapper
├── .run/                        shared IntelliJ run configurations
├── scripts/                     environment checker
└── src/
    ├── main/java/com/selfdriving/
    │   ├── Main.java            entry point
    │   ├── app/                 startup, application context, session
    │   ├── config/              settings
    │   ├── physics/             vehicle dynamics
    │   ├── vehicle/             VehicleState, gears, modes, lights, controller
    │   ├── world/               city, roads, obstacles, pedestrians, traffic
    │   ├── navigation/          road graph, A*, Dijkstra, ETA, re-routing
    │   ├── sensors/             lidar, radar, ultrasonic
    │   ├── autopilot/           speed/steering control, safety controller
    │   ├── simulation/          loop, clock, scenarios
    │   ├── diagnostics/         faults, diagnostics, fixes, tests, issues
    │   ├── alerts/              alert bus
    │   ├── updates/             OTA updates
    │   ├── auth/                users, roles, passwords
    │   ├── persistence/         H2, schema, repositories
    │   └── ui/                  login, driver, admin, technician, render (3D)
    ├── main/resources/          theme.css, db/ (SQL scripts)
    └── test/java/               JUnit tests
```

---

## 8. Status

| Stage | Scope | Status |
|:---:|---|:---:|
| 0 | Project foundation: build, package layout, Git, setup guide, theme, window | ✅ Done |
| 1 | Physics engine, drivable 3D car, Tesla-style driver screen, keyboard driving | ⏳ Next |
| 2 | Road graph, A\*, sensors, autopilot, safety controller, alerts, scenarios | 🔜 |
| 3 | Login, roles, passwords, H2 schema, repositories, trip history | 🔜 |
| 4 | Admin + Technician dashboards, OTA, diagnostics, system tests, packaging | 🔜 |

---

## 9. Glossary

| Term | Meaning |
|---|---|
| **ABS** | Anti-lock Braking System: stops wheels locking so the car can still steer while braking hard |
| **A\*** | Shortest-path algorithm that uses a distance estimate to search faster than Dijkstra |
| **AEB** | Automatic Emergency Braking |
| **ETA** | Estimated Time of Arrival |
| **Fixed timestep** | Physics always advances by the same small time step, for stable, repeatable results |
| **JDBC** | Java's standard API for talking to SQL databases |
| **Lidar** | Laser sensor that measures distances all around the car |
| **OTA** | Over-The-Air software update |
| **Pacejka magic formula** | Widely used empirical formula for tyre force versus slip |
| **PID** | Proportional-Integral-Derivative controller, a classic feedback controller |
| **Pure pursuit** | Steering method that aims at a point a set distance ahead on the path |
| **Regenerative braking** | Using the electric motor as a generator to slow the car and recharge the battery |
| **Slip angle / slip ratio** | How much a tyre slides sideways / spins faster or slower than the road; tyre forces depend on them |
| **TTC** | Time To Collision = distance ÷ closing speed |
| **VehicleState** | The one object holding the car's current state, read by everything else |
