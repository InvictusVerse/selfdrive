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

✅ *Built* (`simulation.SimulationLoop`, `simulation.Simulation`) · database worker 📐 *Stage 3*

| Thread | Rate | Job |
|---|---|---|
| **Simulation thread** | 120 Hz fixed (Δt = 1/120 s ≈ 8.3 ms), physics sub-stepped 8× (960 Hz) | Run queued commands → smooth driver inputs → physics → road tests → publish snapshot |
| **JavaFX UI thread** | Screen refresh (~60 fps) via `AnimationTimer` | Read the latest snapshot, update the 3D scene, gauges, map and telemetry |
| **Database worker** | On demand (Stage 3) | Save trips, alerts, faults and logs without blocking |

A **fixed timestep** makes the physics stable and repeatable: the same inputs always produce the same result, whatever the frame rate. Each tick is scheduled against the clock. On Windows, `LockSupport.parkNanos` rounds up to about 15.6 ms and `Thread.sleep(1)` is accurate to about 2 ms, so the loop sleeps in 1 ms steps and spins for the final ~2 ms. This costs about half a CPU core in total and keeps ticks even. If the loop falls far behind (e.g. under a debugger), it resynchronises instead of trying to catch up.

**How threads talk to each other** (no locks in the hot path):

| Direction | Mechanism |
|---|---|
| UI → simulation, held keys | `DriverInput`: `volatile` booleans |
| UI → simulation, commands (gear, surface, ABS, reset, pause…) | `Simulation.submit(Consumer<Simulation>)`, a `ConcurrentLinkedQueue` run on the simulation thread before the next tick |
| Simulation → UI, state | An immutable `SimulationSnapshot` (with `VehicleState`) swapped into an `AtomicReference` after every tick |
| Simulation → UI, messages | A queue of short notifications shown as toasts (e.g. "Press the brake to shift out of Park") |

Pause and slow motion (×0.25) are on the driver display now and will become Admin settings in Stage 4. Slow motion shortens the time step instead of skipping ticks, so it stays smooth.

### 5.2 Vehicle physics

✅ *Built* (`physics` package) · verified by 25 JUnit tests

The car is a **rigid body** on a flat plane (position x, y and heading ψ, velocities v<sub>x</sub>, v<sub>y</sub> in the car's own frame and yaw rate r), with four wheels, each with its own tyre, spin, brake, suspension corner and load. It is integrated with **semi-implicit Euler** (velocities first, then positions) at 960 Hz. Equations of motion in the rotating car frame:

- *m (v̇<sub>x</sub> − r·v<sub>y</sub>) = ΣF<sub>x</sub>*
- *m (v̇<sub>y</sub> + r·v<sub>x</sub>) = ΣF<sub>y</sub>*
- *I<sub>z</sub> ṙ = Σ (x<sub>i</sub>·F<sub>y,i</sub> − y<sub>i</sub>·F<sub>x,i</sub>)*

**Car parameters** (`VehicleParams.electricSedan()`, similar to a mid-size dual-motor EV sedan):

| Parameter | Value |
|---|---|
| Mass *m* · yaw inertia *I<sub>z</sub>* | 1 850 kg · 2 900 kg·m² |
| Wheelbase *L* · weight split | 2.875 m · 47 % front / 53 % rear |
| Track width · CG height *h* | 1.58 m · 0.46 m |
| Wheel radius *R* · wheel inertia | 0.34 m · 1.4 kg·m² |
| Drag *C<sub>d</sub>* · frontal area *A* · rolling resistance *C<sub>rr</sub>* | 0.23 · 2.22 m² · 0.010 |
| Motor | 300 kW, 460 N·m, 9.0:1 reduction, 16 000 rpm limit, 40/60 front/rear torque split |
| Regeneration | up to 75 kW / 200 N·m |
| Brakes (per wheel, full pedal) | 2 600 N·m front, 1 500 N·m rear (enough to lock all four wheels) |
| Suspension (per corner) | 35 kN/m spring, 3.8 kN·s/m damper; anti-roll bars 45 / 25 kN·m/rad |
| Battery · auxiliary load | 75 kWh · 350 W |
| Max road-wheel angle | ±32° (Ackermann geometry per front wheel) |

**Forces modelled**

| Effect | Model |
|---|---|
| **Tyre grip** | Pacejka *magic formula*: *F = F<sub>z</sub>·D·sin(C·atan(B·s − E·(B·s − atan(B·s))))* |
| Combined slip | *s* = contact-patch sliding speed ÷ max(wheel speed, rim speed, 1 m/s). One number covers braking, accelerating and cornering, and the force always points against the slide, so grip used for braking is not available for cornering (friction circle). A locked wheel (*s* = 1) keeps about 91 % of peak grip on a dry road |
| Slip ratio · slip angle | *κ = (ω·R − v) / \|v\|* · *α = atan(v<sub>lat</sub> / \|v<sub>long</sub>\|)* (shown on the display, used by ABS/TCS) |
| **Wheel spin** | *I·ω̇ = T<sub>drive</sub> − T<sub>brake</sub> − F<sub>x</sub>·R*. Tyres are very stiff compared with a wheel's inertia, so this is solved **implicitly**: the tyre force is linearised around the current spin, which keeps it stable without faking the physics. Brakes are applied last and can hold a wheel at zero (locked) |
| **Weight transfer** | Sprung body with heave, pitch and roll; each corner is a spring-damper *F = k·x + c·ẋ* plus anti-roll bars. Braking pitches the nose down and loads the front tyres; cornering rolls the body and loads the outside tyres. Loads feed straight back into tyre grip |
| **Electric motor** | Constant torque up to base speed, then constant power *T = min(T<sub>max</sub>, P<sub>max</sub> / ω)*, fading out at the rpm limit (sets the top speed) |
| **Regenerative braking** | With the accelerator released in Drive the motor brakes the car and recharges the battery, fading out at walking pace. A slip limiter (the same logic as traction control) keeps regen from locking the wheels on ice |
| **ABS** | Per wheel: when the slip ratio passes 1.3 × the tyre's peak slip, brake pressure is released; below 0.8 × it is re-applied. The wheel cycles around peak grip |
| **Traction control** | Cuts motor torque when a wheel spins past 1.3 × peak slip; restores it gradually |
| **Air drag** · **rolling resistance** | *F = ½·ρ·C<sub>d</sub>·A·v²* (ρ = 1.225 kg/m³) · *F = C<sub>rr</sub>·m·g* |
| **Battery** | Electrical power = motor power ÷ 0.90 when driving, × 0.75 when regenerating, plus auxiliary load. State of charge, average consumption and range update continuously |

**Road surfaces** change the magic-formula coefficients (published simplified values):

| Surface | B | C | D (= μ) | E | Peak slip |
|---|---|---|---|---|---|
| Dry asphalt | 10 | 1.9 | 1.00 | 0.97 | 18 % |
| Wet asphalt | 12 | 2.3 | 0.82 | 1.0 | 9 % |
| Snow | 5 | 2.0 | 0.30 | 1.0 | 31 % |
| Ice | 4 | 2.0 | 0.10 | 1.0 | 39 % |

**How we prove it is real physics.** The JUnit tests drive the model and compare it with textbook formulas and real-world figures. Current results:

| Check | Expected | Measured |
|---|---|---|
| Braking 100 → 0 km/h, dry, ABS | *d = v² / (2·(μ + C<sub>rr</sub>)·g)* = 38.9 m | 39.4 m |
| Braking 100 → 0 km/h, wet · snow · ice | 47.4 m · 126.9 m · 357.5 m | 47.9 m · 124.7 m · 343.9 m |
| ABS off (wheels lock) | longer than with ABS | 42.1 m vs 39.5 m |
| 0 → 100 km/h | 3.8–5.5 s for this class of car | 4.49 s |
| Top speed | limited by motor rpm ≈ 228 km/h | 223.7 km/h |
| Turning radius at walking pace, 15° steer | geometry √((L / tan δ)² + b²) = 10.81 m | 10.83 m |
| Cruise at 100 km/h | 100–220 Wh/km | 135 Wh/km |
| Braking / cornering | front / outside tyres loaded, nose down / body rolls out | ✅ |
| Abuse test (spins, slides, reverse, every surface) | no invalid numbers, nothing runs away | ✅ |

Rolling resistance is added to μ in the braking formula. On dry roads that changes the result by 1 %, but on ice (μ = 0.1) it is a tenth of the grip, so leaving it out would give the wrong answer. The same measurement runs live in the app: brake fully (Space) from speed and the display shows measured vs theoretical distance.

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

✅ *Built:* gears and manual driving (`vehicle` package) · 📐 *Designed (Stage 2):* autonomous mode, override, emergency stop

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

- **Gears** (`GearSelector`): P, R, N, D. Leaving Park needs the brake pressed; Park needs the car stopped (< 0.5 m/s); Drive ↔ Reverse needs walking pace (< 1.5 m/s); Neutral is always allowed. A refused shift shows the reason on screen.
- **Manual driving** (`DriverControls`): keys are turned into smooth pedal and steering positions. The accelerator ramps up in 0.5 s, the brake in 0.6 s (Space: full brake in 0.08 s), and the steering turns at a limited rate and self-centres. Steering is speed-sensitive: at speed a full key press asks for less wheel angle, sized for 12 m/s² of lateral acceleration. That's a little above dry-road grip, so the car can still be pushed into a slide.
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

✅ *Built:* driver display, 3D car and proving ground (`ui.driver`, `ui.render`, `world`) · 📐 *Designed:* login (Stage 3), Admin and Technician dashboards (Stage 4), city, route and obstacles (Stage 2)

- **Look:** dark Tesla-style theme (`theme.css`, with matching canvas colours in `ui.Palette`): near-black background, soft grey cards, one accent blue, and green/amber/red for status.
- **Window size:** the app opens at 1600 × 900 if the desktop has room. Otherwise it opens maximised. Sizes are in scaled pixels, so Windows display scaling is handled (e.g. 1920 × 1080 at 125 % = 1536 × 864). The layout works down to 1280 × 720; below 1420 px wide the small group labels in the bottom bar are hidden.
- **Driver display** (`DriverScreen`), laid out like an EV centre screen:

  | Area | Contents |
  |---|---|
  | 3D view (left) | Live car and road; speed, P R N D, battery % and range top-left; drive mode, ABS/TCS lights and the running 0–100 timer top-right; camera name bottom-left; toasts; keyboard help panel |
  | Map (top right) | Proving ground drawn from the road data (north up), car marker, recent path, braking zone, 100 m scale bar; works offline |
  | GRIP · g-g | The car's acceleration in g with a trail, and the road's grip limit μ as a dashed circle: the friction circle made visible |
  | TYRES | The four tyres from above, coloured by grip in use (green < 70 %, amber < 95 %, red at the limit), steering angle, force arrow, load in kN, slip %, ABS marker |
  | ENERGY | Power bar (white = drawing power, green = regenerating), power, motor rpm, battery, range, average consumption, trip, and the latest braking and 0–100 results |
  | Dock (bottom) | P R N D · Dry / Wet / Snow / Ice · ABS · TCS · View · Forces · Slow-mo · Pause · Reset · Keys |

- **Keyboard:** W/↑ accelerate · S/↓ brake · A D/← → steer · Space full brake · 1–4 = P R N D · G surface · B ABS · T TCS · C camera · F forces · M slow motion · P pause · Backspace reset · H help · F11 full screen. Dock buttons never take keyboard focus, so driving keys always reach the car; every button also has a shortcut and a tooltip. If the window loses focus, all keys are released so the car doesn't keep accelerating.
- **Touchscreen and mouse** (`TouchControls`, toggle with O or the Touch button):
  - An on-screen steering wheel: drag left or right, and it centres itself when released.
  - BRAKE and ACCEL pedals: press and hold. The fill bar shows the real pedal position, whether it comes from the screen or the keyboard.
  - Tappable P R N D, both on the display and in the bottom bar. A tap shifts directly while the car is stopped, because one finger or mouse can't hold the brake and tap at the same time. The car holds itself on the brake, as touchscreen drive selectors do. Keyboard shifts still need the brake held.
  - Each control tracks its own touch point, so one finger can steer while another presses a pedal. Keyboard and screen inputs are separate sources, so releasing a key never releases an on-screen pedal.
- **Road tests run automatically** (`PerformanceMonitor`). Pressing the brake fully above 18 km/h starts a braking test that ends when the car stops, and shows *measured vs v²/(2(μ+C<sub>rr</sub>)g)*. Pulling away from a stop at full throttle in Drive times 0–100 km/h. Lifting off or braking cancels the run.
- **3D models generated in code** (`CarModel`, `WorldModel`, `MeshFactory`):
  - The car body is *lofted*: 44 rounded-box cross-sections whose height and width follow smooth side and plan profiles (monotone cubic curves, so no bumps). A dark glass cabin is lofted the same way.
  - Wheels spin at their real speed, the front wheels steer with their individual Ackermann angles, and the body pitches, rolls and heaves with the suspension.
  - Tail lights brighten when braking, and reverse lights come on in R.
  - The world is ground with a 20 m grid (so motion is visible off-road), asphalt, lane paint, a braking zone with a marker every 10 m, and light poles, each merged into one mesh.
  - No model files.
- **Force arrows** (F): one cyan arrow per tyre showing the force it puts on the road (1 m ≈ 2.5 kN).
- **Cameras** (C): chase, autopilot (high behind), top-down (heading-up) and side (to watch pitch, roll and wheel spin). The chase camera trails the car's heading slightly so slides are easy to see.
- **Accessibility:** buttons have text labels, tooltips and accessible help text, and everything can be driven from the keyboard. Status is never shown by colour alone: ABS/TCS lights change their text (e.g. "ABS OFF") and tyres show numbers. Inactive tell-tales are deliberately dim and do not meet text-contrast guidelines. Proper validation needs testing with a screen reader and an accessibility review.
- **Developer tool** (`DevAutomation`): `-Dselfdrive.script="…"` plays a timed script (pedals, gears, surface, camera, screenshots) for checking visuals and making documentation images without a person at the keyboard.

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

**Tests** (38 so far, added with each stage):

| Test class | Covers |
|---|---|
| `TireModelTest` | Free rolling gives no force, peak = μ·load, locked-wheel sliding grip, lateral force direction, friction circle |
| `VehicleModelTest` | Braking distance on all four surfaces vs theory, ABS vs locked wheels, 0–100, top speed, standstill in P and D, turning radius, cruise consumption, weight transfer, regen on ice, abuse test on all surfaces |
| `SimulationTest` | The whole loop as the UI uses it: refused shift out of Park, driving off and timing 0–100, automatic braking test, reset |
| `GearSelectorTest` | Every gear rule |
| `ProvingGroundTest` | Polyline offset and dashes, circuit length, start position |

Planned: A\* vs Dijkstra on the same graph, PID and pure pursuit behaviour, safety-controller rules (brakes when TTC is low), permissions (a driver cannot delete users), and repository round-trips with an in-memory H2 database.

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
| 1 | Physics engine, drivable 3D car, Tesla-style driver screen, keyboard driving, road tests | ✅ Done (38 tests) |
| 2 | Road graph, A\*, sensors, autopilot, safety controller, alerts, scenarios | ⏳ Next |
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
