# Self-Driving Car Control System: Project Guide

> A complete, documented reference implementation of a self-driving car control system · Java desktop application for Windows
>
> This is the main reference for the whole project: what it is, what it uses, and how every part works.
> Each section is marked **✅ Built** and names the code that implements it.

---

## Contents

1. [What the project is](#1-what-the-project-is)
2. [Requirements](#2-requirements)
3. [Technology and why](#3-technology-and-why)
4. [Architecture](#4-architecture)
5. [How it works](#5-how-it-works)
   - [5.1 Simulation loop and threads](#51-simulation-loop-and-threads)
   - [5.2 Vehicle physics](#52-vehicle-physics)
   - [5.3 Sensors and the world they see](#53-sensors-and-the-world-they-see)
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
   - [5.14 City traffic](#514-city-traffic)
   - [5.15 Automatic parking](#515-automatic-parking)
   - [5.16 Lights](#516-lights)
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
| A1 | Admin: user management (name, role, access level → confirmation) | `service.UserService`, `ui.admin.UsersPage` |
| A2 | Admin: system settings (configuration → confirmation) | `service.SettingsService`, `ui.admin.SettingsPage` |
| A3 | Admin: software updates (package → update confirmation) | `service.UpdateService`, `ui.admin.UpdatesPage` |
| A4 | Admin dashboard: users table, settings, update status, performance graphs, alerts | `ui.admin`, `ui.common.AlertLogPage` |
| D1 | Driver: set destination (address → route + ETA) | `navigation`, `ui.driver` |
| D2 | Driver: monitor car status (request → real-time status) | `vehicle`, `ui.driver` |
| D3 | Driver: override autonomous control (command → control status) | `vehicle`, `autopilot`, `ui.driver` |
| D4 | Driver dashboard: route management, car status, override panel, navigation history, alerts | `ui.driver`, `ui.common.TripHistoryPage`, `ui.common.AlertLogPage` |
| T1 | Technician: diagnose issues (command → diagnostic report) | `diagnostics`, `service.MaintenanceService`, `ui.technician` |
| T2 | Technician: fix issues (command → fix confirmation) | `service.MaintenanceService`, `ui.technician.IssuesPage` |
| T3 | Technician: run system tests (command → test results) | `simulation.SystemTests`, `ui.technician.SystemTestsPage` |
| T4 | Technician dashboard: diagnostic tools, issue tracking, tests, maintenance history, alerts | `ui.technician` |
| S1 | Obstacle detection | `sensors`, `autopilot` |
| S2 | Route optimisation | `navigation` |

---

## 3. Technology and why

| Area | Technology | Version | Why this one |
|---|---|---|---|
| Language | **Java** | 21 LTS | Mature, strongly typed, fast; long-term support release |
| UI | **JavaFX** | 21.0.12 | Modern Java desktop UI with CSS styling, charts, tables and animation |
| 3D | **JavaFX 3D** | (part of JavaFX) | Same toolkit as the UI, so the 3D view sits inside the dashboard. Meshes are generated in code; an optional glTF car model is read by our own loader |
| Physics | **Own engine** | — | Every equation is in our code and can be explained and tested |
| Database | **H2** (embedded) | 2.5.252 | Full SQL database that runs inside the app; nothing to install ([details](#512-database)) |
| DB access | **JDBC** | (part of Java) | Standard Java database API; plain SQL with `PreparedStatement` |
| Password hashing | **PBKDF2-HMAC-SHA256** | (part of Java) | Built into the JDK; no extra library |
| Build | **Maven** via wrapper | 3.9.16 | Standard Java build tool; the wrapper means nobody installs Maven |
| Tests | **JUnit** | 6.1.3 | Standard Java test framework |
| Packaging | **jpackage** | (part of JDK) | Produces a Windows app folder with `.exe` and bundled Java |
| Map data | **OpenStreetMap** (bundled) | ODbL | A real Indian city with lanes, signals and buildings, used offline |
| Version control | **Git + GitHub** (private) | — | History, backup, sharing with teammates |

**Deliberately not used:** Spring (no server needed), game engines such as jMonkeyEngine or LibGDX (one toolkit is simpler), physics libraries (we want to explain our own), online map services at run time.

---

## 4. Architecture

The code is layered. Each layer only talks to the layer below it.

```text
┌─────────────────────────────────────────────────────────────────────┐
│  PRESENTATION  ui.login · ui.driver · ui.admin · ui.technician      │
│                ui.render (3D)                                       │
├─────────────────────────────────────────────────────────────────────┤
│  SERVICES      service (sign-in, users, settings, history,          │
├─────────────────────────────────────────────────────────────────────┤
│  CONTROL       navigation → autopilot → safety controller           │
│                sensors ──────────────────┘                          │
├─────────────────────────────────────────────────────────────────────┤
│  SIMULATION    simulation loop · physics · world · traffic          │
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
4. **No slow work on the UI or simulation thread.** Database writes and loads happen on background threads.
5. **Permissions are checked in services**, not just by hiding buttons.

**Patterns used:** layered architecture, MVC for screens, Observer (alert bus, simulation listeners), Strategy (path finders, tyre models), State (drive modes, update lifecycle), Repository (database access), Command (override and diagnostic commands).

---

## 5. How it works

### 5.1 Simulation loop and threads

✅ *Built* (`simulation.SimulationLoop`, `simulation.Simulation`, `service.DbWriter`)

| Thread | Rate | Job |
|---|---|---|
| **Simulation thread** | 120 Hz fixed (Δt = 1/120 s ≈ 8.3 ms), physics sub-stepped 8× (960 Hz) | Run queued commands → smooth driver inputs → physics → road tests → publish snapshot |
| **JavaFX UI thread** | Screen refresh (~60 fps) via `AnimationTimer` | Read the latest snapshot, update the 3D scene, gauges, map and telemetry |
| **Database writer** | On demand | Save trips and alerts from the simulation, in order, without blocking it |
| **UI workers** (2) | On demand | Sign-in checks and page data loads, so the UI never waits for the database |
| **Update installer** | On demand | Downloads and installs a software update in the background |

A **fixed timestep** makes the physics stable and repeatable: the same inputs always produce the same result, whatever the frame rate. Each tick is scheduled against the clock. On Windows, `LockSupport.parkNanos` rounds up to about 15.6 ms and `Thread.sleep(1)` is accurate to about 2 ms, so the loop sleeps in 1 ms steps and spins for the final ~2 ms. This costs about half a CPU core in total and keeps ticks even. If the loop falls far behind (e.g. under a debugger), it resynchronises instead of trying to catch up.

**How threads talk to each other** (no locks in the hot path):

| Direction | Mechanism |
|---|---|
| UI → simulation, held keys | `DriverInput`: `volatile` booleans |
| UI → simulation, commands (gear, surface, ABS, reset, pause…) | `Simulation.submit(Consumer<Simulation>)`, a `ConcurrentLinkedQueue` run on the simulation thread before the next tick |
| Simulation → UI, state | An immutable `SimulationSnapshot` (with `VehicleState`) swapped into an `AtomicReference` after every tick |
| Simulation → UI, messages | A queue of short notifications shown as toasts (e.g. "Press the brake to shift out of Park") |

Other parts of the system hear from the simulation through `SimulationListener` (trip started and ended, alert raised and acknowledged), called on the simulation thread and handed straight to the database writer. Services that need an answer from the car (a diagnostic scan, "is it parked?") queue a command and wait for its result.

Pause and slow motion (×0.25) are on the driver display. Slow motion shortens the time step instead of skipping ticks, so it stays smooth.

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

### 5.3 Sensors and the world they see

✅ *Built* (`sensors`, `world`, `world.map`) · verified by `SensorTest`, `NavigationTest`

**The world** (`World`) is the proving ground (a test circuit with a braking zone and a car park) plus a **real city area**: about 1.6 × 1.2 km of **Bengaluru around MG Road**, taken from OpenStreetMap and bundled with the app, so nothing is downloaded at run time. A 60 km/h access road joins the two.

| From the map | In the simulation |
|---|---|
| Roads with their class, lanes, one-way flags and speed limits | 480 lane-level links (one per direction of travel), 255 junctions, 1 099 turn paths |
| Traffic signals | 14 signalised junctions with phase plans |
| Building outlines and heights | 974 buildings, extruded in 3D and solid for sensors and collisions |
| Parks and named places | green areas and 11 destinations (10 in the city, plus the car park) |

The importer (`tools.MapImport`, a developer tool) queried OpenStreetMap once through the Overpass API and wrote a compact JSON file. Map data © OpenStreetMap contributors, available under the Open Database Licence (ODbL); the attribution is shown on the map card. Building names and detailed tags were dropped; only geometry, heights and broad types are kept.

**Traffic keeps to the left**, as in India. Lanes are numbered from the kerb: lane 0 is the leftmost, the highest number is the overtaking lane next to the centre line.

**Sensors** are simulated by **ray casting**: straight lines from the sensor, tested against every obstacle's box (slab method) and against building walls (a grid index keeps this fast). A cheap distance check first drops obstacles that are out of range.

| Sensor | Coverage | Returns | Rate |
|---|---|---|---|
| **Lidar** (roof) | 360°, 100 m, 720 rays (0.5° apart, so rays are 35 cm apart at 40 m and even a pedestrian is always hit) | Distance per ray, ±3 cm noise | 60 Hz |
| **Front radar** (bumper) | ±8°, 160 m, 33 rays | Range and **closing speed** (Doppler: relative velocity along the line of sight) of the nearest object | 60 Hz |
| **Ultrasonic** ×8 (bumpers) | 5 m, corners, front and rear | Short distances | 60 Hz |

**Perception** turns returns into a list of `DetectedObject`s: anything a lidar ray or the radar hit, with measured position (±5 cm noise), size and velocity. Buildings count as map data, not objects. Each sensor can fail (see [5.8](#58-diagnostics-faults-and-system-tests)); the rest keep working, and the autopilot refuses to start with no forward sensor.

**Collisions** (`CollisionSystem`): the car's footprint is tested against nearby obstacles and walls with the separating axis theorem. On contact the car is pushed out and loses the speed that went into the object (15 % bounce). A collision raises a critical alert and hands control back to the driver.

### 5.4 Navigation and route optimisation

✅ *Built* (`world.RoadNetwork`, `navigation`) · verified by `NavigationTest`

**Lane-level road network** (`RoadNetwork`), built from the map at start-up:
1. Points shared by two or more roads become junctions; junctions joined by very short pieces of road (where two divided roads cross) are merged into one.
2. Each road between junctions becomes one link per direction, trimmed back to the junction's edge.
3. Inside each junction, turn paths join incoming to outgoing lanes: left turns from the leftmost lane, right turns (across oncoming traffic) from the rightmost, straight on from any lane. The paths are smooth curves with a real turning radius.
4. Turn paths that cross or merge get a **conflict** each way, marked with who gives way: at signals, turning traffic yields to straight-on traffic; elsewhere the more important road has priority, then traffic from the right.
5. Signalised junctions get a plan: approaches are grouped by direction into phases that take turns (green, amber, all-red).

The network is immutable and shared by all threads.

**Fastest route** (`PathFinder`): the search runs over links, not junctions, so the car can start part-way along a road and U-turns are never planned. Cost is travel time at the speed limit; closed roads are skipped.
- **A\***: the heuristic is straight-line distance ÷ the highest speed limit. It never overestimates, so the result is still the fastest route.
- **Dijkstra**: the same search with no heuristic. The tests check that both give identical travel times to every place, and that A\* examines fewer roads.

**Drivable path** (`Route`): the lanes and turn paths along the route are joined into one path, sampled every 1.5 m. Where the next turn needs another lane, a smooth lane change is planned before the junction. Each point gets a **target speed**:
1. the road's limit,
2. capped by comfortable cornering, *v = √(a<sub>lat</sub> / curvature)*, where the allowed lateral acceleration falls from 3.0 m/s² at walking pace to 1.6 m/s² at speed (drivers accept less sideways push the faster they go),
3. then a backward pass so the car can always brake at 2 m/s² in time for the next corner and for the stop at the destination: *v<sub>i</sub> = min(v<sub>i</sub>, √(v<sub>i+1</sub>² + 2·a·Δs))*.

Turn-by-turn directions ("Turn right onto MG Road") come from the junctions on the route. Destinations are reached at the kerb in lane 0.

**Progress** (`RouteTracker`): projects the car onto the path, searching only near its last position, so it can't jump to a later part of the route that passes nearby. It gives distance and time remaining, the next direction, the distance from the path, and whether the car has left the route (> 12 m).

**Re-routing:** closing a road (test scenario) re-plans from where the car is. The scenario only closes a road that has a way around it.

### 5.5 Autopilot

✅ *Built* (`autopilot`) · verified by `AutopilotIntegrationTest`

Every tick the autopilot picks a **target speed**, the lowest of:
- the route's speed profile (read slightly ahead, so it starts slowing in time),
- the maximum autopilot speed (a setting, 10–130 km/h, default 100; the driver can also change it with − / +),
- a safe speed behind anything in the lane ahead. It keeps a gap of 4 m + *T* × the other object's speed, where the time gap *T* is a setting (default 1.2 s), and never goes faster than lets it stop comfortably before it (*v² = v<sub>lead</sub>² + 2·2.5·(gap − wanted gap)*),
- a stop at the junction's stop line when the junction planner says so.

When something is ahead, the autopilot also **plans the braking**. Once a constant deceleration of 1.2 m/s² is needed to stop at the wanted gap, it brakes at exactly the rate *a = (v² − v<sub>lead</sub>²) / (2·d)* that ends there. This is fed forward, so the car doesn't lag behind the plan and doesn't need emergency braking for a car it has seen.

**Junctions** (`JunctionPlanner`), using only what the sensors report plus the map and the light's state:
- **Traffic lights:** stop at the stop line on red; on amber stop if that is possible at a comfortable deceleration, otherwise continue.
- **Giving way:** where the car's turn path crosses one with priority, it waits while a vehicle it can see is on that path before the crossing point, or will reach it within 3 s of the car.
- If everyone is waiting for everyone, it goes carefully.

**Lane changes and overtaking** (`LaneChangePlanner`):
- **Overtaking:** stuck behind a slow *moving* vehicle for a few seconds, on a road with a free lane to the right and enough road before the next junction, it indicates, moves over, passes, and the route brings it back into the lane it needs. Stopped vehicles are not overtaken: they may be queuing.
- **Keeping left:** back into the left lane when nothing needs passing.
- **Blind spot:** a lane change waits while a vehicle is alongside or closing fast in the target lane.
- A change is made by re-planning the route from where the car is, so steering, speeds and indicators all follow the new path.

**Indicators:** on 45 m before a turn and off 20 m after it; about 3 s (30 m) before a lane change.

**Speed control** (`SpeedController`):
1. A **PI controller** (K<sub>p</sub> 1.0, K<sub>i</sub> 0.25, integral clamped against wind-up) turns the speed error into a wanted acceleration.
2. Air drag and rolling resistance are added back as feed-forward.
3. Positive demand becomes accelerator, sized by the force the motor can give at this speed.
4. Slowing down uses **regeneration first**, blended from 0 to 100 %, and the friction brakes only for what regen can't deliver.

**Steering** (`PurePursuit`): aims at a point on the path ahead and steers along the circle through it, *δ = atan(2·L·sin α / L<sub>d</sub>)*, measured from the rear axle. The look-ahead grows with speed (2.5 m + 0.55 s × speed, 4–25 m) and shortens in tight turns so the car follows the curve instead of cutting it. The steering angle changes at most 60°/s.

**Arrival:** within 4 m of the end and stopped, the car shifts to Park, hands back control and reports "Arrived at …".

**Measured in the tests:**

| Drive | Result |
|---|---|
| Proving ground to a city destination (3.2 km) | Arrives in 6 min 5 s, 100 % on autopilot, 0.60 kWh, stops within 8 m of the kerb point, indicates before every turn on the correct side, no collisions |
| Across the city | Lane error below 0.9 m, never more than 2 km/h over the limit, slower in tight corners |
| Through 120 other vehicles | Arrives; never drives through a red light; gives way; no collisions; no emergency braking needed |
| Stopped car ahead | Stops behind it without emergency braking and follows when it pulls away |
| Slow auto-rickshaw ahead on a wider road | Overtakes it and returns to its lane |
| Crossing pedestrian | Stops with a gap of several metres |
| Road closed ahead | Re-routes and still arrives |

### 5.6 Safety controller

✅ *Built* (`SafetyController`) · verified by `SafetyControllerTest` and integration tests

Always on, in manual driving and on autopilot, and it overrules both. Every tick it **predicts the next 3 seconds** in 0.05 s steps:
- the car moves along its route (autopilot), or along its current arc at constant speed and yaw rate (manual),
- vehicles move **along their lanes** (a car in a curve is predicted round the curve, not straight on); other objects move with their measured velocity. Vehicles following behind the car are ignored.

The first moment the car's footprint, plus a 35 cm margin, would overlap an object is the **time to collision (TTC)**.

| Check | Rule | Action |
|---|---|---|
| **Forward collision warning** | TTC < 2.5 s | Amber banner on the display, warning alert |
| **Automatic emergency braking** | Distance to impact < stopping distance *v² / (2·0.85·μ·g)* + *t<sub>r</sub>* · v + 1.5 m, or TTC < 0.6 s. The reaction allowance *t<sub>r</sub>* is a setting (default 0.25 s) | Full brakes with ABS and full regen, red banner, the object shown red, critical alert. Holds until the car has stopped and the path is clear |
| Autopilot without forward sensors | lidar and radar both failed | Autopilot refuses to start |
| Autopilot with a brake fault | brake pressure fault present | Autopilot refuses to start, and hands over if it is driving |

Lower grip (wet, snow, ice) means a longer stopping distance, so emergency braking starts earlier. **Priority order:** emergency stop → emergency braking → driver → autopilot.

### 5.7 Drive modes and override

✅ *Built* (`vehicle.DriveMode`, `Simulation`)

```text
      ┌────────────┐   brake or steer (or Take over)   ┌──────────┐
      │ AUTOPILOT  │ ────────────────────────────────▶ │  MANUAL  │
      │ AUTO PARK  │ ◀──────────────────────────────── │          │
      └─────┬──────┘   Start autopilot / Park          └────┬─────┘
            │                                               │
            └───────────────┐  Emergency stop (X)  ┌────────┘
                            ▼                      ▼
                       ┌─────────────────────────────┐
                       │ EMERGENCY STOP: full brakes │ ── stopped: Park ──▶ MANUAL
                       └─────────────────────────────┘
```

- **Autopilot** needs a route. It shifts to Drive by itself if the car is stopped in Park or Neutral, and isn't available in Reverse. Pressing the accelerator on autopilot adds speed without switching it off. Touching the brake or steering, by key or on screen, hands control back at once.
- **Emergency stop** brakes to a stop, whoever is driving, switches on the hazard lights, then secures the car in Park.
- **Gears** (`GearSelector`): P, R, N, D. Leaving Park needs the brake pressed; Park needs the car stopped (< 0.5 m/s); Drive ↔ Reverse needs walking pace (< 1.5 m/s); Neutral is always allowed. A refused shift shows the reason on screen. While a software update installs, only Park is allowed.
- **Manual driving** (`DriverControls`): keys are turned into smooth pedal and steering positions. The accelerator ramps up in 0.5 s, the brake in 0.6 s (Space: full brake in 0.08 s), and the steering turns at a limited rate and self-centres. Steering is speed-sensitive: at speed a full key press asks for less wheel angle, sized for 12 m/s² of lateral acceleration, a little above dry-road grip, so the car can still be pushed into a slide.

### 5.8 Diagnostics, faults and system tests

✅ *Built* (`diagnostics`, `simulation.SystemTests`, `service.MaintenanceService`) · verified by `SystemTestsTest`, `MaintenanceAndUpdatesTest`

**Faults** have trouble codes in the usual format (P = powertrain, C = chassis, U = network and sensors) and a **real effect** on the car:

| Code | Fault | Effect in the simulation | Repair |
|---|---|---|---|
| U0301 | Lidar not responding | No lidar points | Reconnect and recalibrate the lidar |
| U0302 | Front radar not responding | No radar target | Clean and realign the radar |
| U0303 | Parking sensors not responding | No close-range distances; automatic parking unavailable | Replace the sensor harness |
| C1020 | Brake pressure low | Friction brakes give 40 % of their force; autopilot unavailable | Bleed the brake lines, top up the fluid |
| P0A2F | Drive motor too hot | Motor power limited to 35 % | Clear the coolant circuit, replace the pump |
| P0A7F | Battery cell imbalance | Power limited to 60 %, no regenerative braking | Balancing charge, replace the weak module |

- **Fault injection** (technicians with full access): any fault can be switched on to see how the car and its driver react. It raises an alert and the HUD shows **SERVICE** with the codes.
- **Diagnostic scan:** reads each subsystem live (lidar returns, radar response, the eight parking sensors, brake line pressure, motor temperature and power limit, battery charge and cell spread, steering) and reports OK, warning or fault with the reading and code. Each fault found opens an issue, one per code until it is closed.
- **Issues** move `OPEN → IN_PROGRESS → FIXED → VERIFIED → CLOSED`. *Start work* assigns it; *Apply the fix* repairs the car (the fault clears and its effect ends); *Verify* runs a fresh scan and **reopens** the issue if the fault is still there; *Close* finishes it. Technicians can also report problems by hand; those need a note saying what was done.
- **System tests:** eight functional tests, each driving a separate copy of the car on the empty proving ground with the real car's current faults, so the car in use is not disturbed:

  | Test | Pass mark |
  |---|---|
  | Lidar detection | at least 3 rays hit an object 20 m ahead |
  | Radar tracking | range to an object 40 m ahead within 0.5 m |
  | Parking sensors | rear sensors read 1.20 m ± 0.15 m |
  | Brakes: stop from 50 km/h | within 1.2 × the ideal *v²/(2μg)* (healthy: 10.1 m vs 9.8 m; with C1020: 12.5 m) |
  | Motor: 0–50 km/h | within 3.5 s (healthy: 2.46 s; with P0A2F: 6.75 s) |
  | Battery: regenerative charging | charging at more than 10 kW while coasting from 60 km/h (healthy: 56 kW) |
  | Emergency braking: stopped car ahead | stops before a car 45 m ahead with the accelerator held |
  | Steering response | yaw rate above 0.15 rad/s at 30 km/h with 30 % steering |

  Each result is saved with its measurement and duration; a run takes about a second.
- **Maintenance log:** every scan, injection, repair, verification and test run, with the technician and time.

### 5.9 Alerts

✅ *Built* (`alerts.AlertBus`, `service.Recorder`)

One central **alert bus** (Observer pattern). Any part of the system publishes an alert, and screens and the database recorder subscribe to it.

- **Severity:** `INFO`, `WARNING`, `CRITICAL`.
- **Categories:** obstacle, collision, sensor, brakes, navigation, autopilot, battery, system, security, maintenance, update.
- **No flooding:** the same message from the same source is not raised again within 5 s.
- **On the driver display:** warnings appear as short toasts. Critical alerts appear as cards on the 3D view that stay until acknowledged with ✕.
- **Saved:** every alert goes into the database; acknowledging one records who did it and when. The **Alerts** page lists them all, filtered by severity and category, and critical ones can be acknowledged there too.
- **Security alerts:** three wrong passwords in a row for an account raise a warning; the lock-out after five raises another.

### 5.10 Software updates (OTA)

✅ *Built* (`service.UpdateService`) · verified by `MaintenanceAndUpdatesTest`

An update is a package of setting changes with a **SHA-256 checksum**. *Check for updates* lists the versions the (simulated) server offers that are newer than the installed one:

| Version | Changes |
|---|---|
| 2.1.0 | Following distance 1.5 s (was 1.2 s) |
| 2.2.0 | Emergency braking reaction allowance 0.35 s (was 0.25 s) |
| 2.3.0 | Autopilot limit 90 km/h. Its first download arrives corrupted, to show a failed install; downloading again works |

**Installing:** `AVAILABLE → DOWNLOADING` (the package arrives in 20 chunks with a progress bar) → checksum check → `INSTALLING` (the car is held in Park) → `COMPLETED`: the settings change, the software version changes and an alert tells the driver. A package whose checksum does not match goes `FAILED → ROLLED_BACK` and nothing is changed.

**Rules:** only with the car stopped in Park and nobody driving; one install at a time; versions in order. A completed update can be **rolled back** (newest first): the values it replaced are stored with it and restored.

### 5.11 Users, roles and security

✅ *Built* (`auth`, `service.AuthService`, `service.UserService`) · verified by `PasswordHasherTest`, `AuthServiceTest`, `UserServiceTest`

- **Roles and access levels.** Each user has a role and a level; *standard* covers everyday work and *full* adds the more powerful tools:

  | Role | Standard access | Full access adds |
  |---|---|---|
  | Admin | users, settings, updates, performance, alerts, audit log, drive, own trips | everyone's trips, database console |
  | Driver | drive, own trips, alerts | test scenarios |
  | Maintenance Technician | diagnostics, fixes, system tests, issues, alerts | fault injection, driving |

- **Passwords** are never stored as plain text. Each gets a random 16-byte salt and is hashed with **PBKDF2-HMAC-SHA256**, 600 000 iterations (the OWASP recommendation), stored as `pbkdf2-sha256$iterations$salt$hash` so the cost can be raised later. Checking compares in constant time, and password arrays are wiped after use. Rules: at least 8 characters with letters and a digit, not the username.
- **Sign-in protection:** a wrong password and an unknown username get the same answer, and an unknown name still costs a full hash, so the screen does not reveal which accounts exist. After 5 wrong passwords in a row the account is locked for a minute. A password reset by an admin unlocks it.
- **Checks in the service layer:** every action checks the user's permissions, whatever the screen shows. An admin cannot delete, deactivate or demote their own account, and the last active admin with full access cannot be removed.
- **Audit log:** sign-ins, failures, lock-outs, user changes, password changes, settings changes, fault injections, fixes, updates and database console use, with who and when.
- **SQL injection:** all queries use `PreparedStatement` parameters. User input is never concatenated into SQL.
- **Demo accounts** are created on first start (see [SETUP.md](SETUP.md)). The sign-in screen lists them only while they still have their published passwords. Change them if the app is used anywhere else.

### 5.12 Database

✅ *Built* (`persistence`) · verified by `DatabaseTest`

**H2, embedded mode.** H2 is a full relational SQL database written in Java. It runs *inside* the program and stores everything in one file, so there is no server to install.

| Option | Verdict for this project |
|---|---|
| **H2 (chosen)** | ✅ Pure Java, embedded, full SQL with transactions, single file, built-in web console, MySQL compatibility mode |
| SQLite | 👍 Also embedded, but uses a native library, has weaker type checking and limited `ALTER TABLE` |
| MySQL / PostgreSQL | 👎 Every PC running the app would need a server installed and configured, with no benefit for a single-user desktop app |

**Where the data lives:** `data\selfdriving.mv.db` next to where the app was started; for the packaged app `%LOCALAPPDATA%\SelfDrive\data` (the app folder may be read-only). The `selfdrive.dataDir` system property overrides both.

**Schema migrations:** versioned SQL scripts in `src/main/resources/db/` (listed in `migrations.txt`) run once each at start-up, in a transaction, and are recorded in `schema_version`. A released script is never edited; changes go into a new one.

| Script | Tables |
|---|---|
| V1 | `users` (case-insensitive unique usernames), `settings`, `alerts`, `trips`, `audit_log` |
| V2 | `issues`, `maintenance_log`, `test_runs`, `test_results`, `software_updates` |
| V3 | update failure details and the values to restore on roll back |

**Threads:** the 120 Hz simulation never waits for the disk. Trips and alerts are handed to one background writer thread (`DbWriter`) that saves them in order; screens load data on background workers. One idle connection keeps the database open for the app's lifetime.

**Trips** start when a route is planned and end on arrival (*arrived*) or when the route is cancelled, replaced or the driver signs out (*cancelled*). Each records origin, destination, planned and driven distance, time, energy and the share driven on autopilot. Trips left open when the app closed are marked cancelled at the next start.

**Inspecting the database:** full admins can open H2's web console from the Overview page. It listens on this PC only and closes with the browser session. The file can also be opened in DBeaver while the app is closed.

**If MySQL is ever required:** the SQL is standard and goes through JDBC, so switching means changing the JDBC URL and driver dependency. `MODE=MySQL` keeps the SQL compatible in the meantime.

### 5.13 User interface and 3D

✅ *Built* (`ui`, `ui.login`, `ui.driver`, `ui.admin`, `ui.technician`, `ui.common`, `ui.render`)

- **Look:** dark Tesla-style theme (`theme.css`, with matching canvas colours in `ui.Palette`): near-black background, soft grey cards, one accent blue, and green/amber/red for status.
- **Window size:** opens at 1600 × 900 if the desktop has room, otherwise maximised. Sizes are in scaled pixels, so Windows display scaling is handled. The layout works down to 1280 × 720.
- **Sign-in** (`LoginScreen`): username and password, Enter to submit; the check runs in the background. The demo accounts are listed underneath while unchanged; clicking one fills the form.
- **Navigation rail** (`AppShell`): the pages the user's role and access level allow, with the user's initials, *Password* (change your own) and *Sign out* at the bottom. Signing out cancels any trip and secures the car.

  | Page | Who | Contents |
  |---|---|---|
  | Overview | Admin | The car right now, software version and updates, users and lock-outs, trips, alerts in the last 24 h, open issues and the last test run; latest critical alerts and activity; database console |
  | Drive | Driver, Admin, full Technician | The driver display (below) |
  | Diagnose | Technician | Scan results per subsystem; fault list with injection (full access) |
  | Issues | Technician | Issue table and the next step for the selected issue; report an issue |
  | Tests | Technician | Run all system tests; results with measurements and durations; earlier runs |
  | History | Technician | Maintenance log |
  | Trips | Driver, Admin | Trip history with totals (distance, energy, average Wh/km, autopilot share); all drivers for full admins |
  | Users | Admin | Accounts table; create, edit role, access level and status, reset password, delete; shows what the chosen role and level can do |
  | Settings | Admin | Maximum autopilot speed, following distance, emergency braking on/off and reaction allowance, traffic amount; applied to the car at once |
  | Updates | Admin | Check, install with live progress, roll back |
  | Stats | Admin | Live speed and battery-power graphs (last 2 minutes), energy per trip, latest road tests |
  | Alerts | everyone | Alert log with filters and acknowledgement |
  | Audit | Admin | Audit log with a filter |

- **Driver display** (`DriverScreen`), laid out like an EV centre screen:

  | Area | Contents |
  |---|---|
  | 3D view (left) | Live car, city, traffic, route and lidar points; speed, P R N D, battery and range; tell-tales for indicators, dipped and main beam, hazards and the autopilot; drive mode, SERVICE (fault codes) or UPDATING, ABS/TCS, clock and outside temperature top-right; speed-limit sign; traffic-light chip; next-turn banner; collision warning and emergency braking banners; critical alert cards; toasts; keyboard help |
  | Navigation · Autopilot | Destination with Go (A\* route) and ✕; distance and time left; next direction and what the autopilot is doing; Start autopilot / Take over; Emergency stop; Park; maximum speed − / +; TEST row (Pedestrian, Stopped car, Slow car, Closed, Clear) for users allowed to run scenarios; ROAD row (traffic amount, AEB) |
  | Map | Roads, buildings, the route, closed roads, traffic, car marker and trail; follows the car or shows the whole area (click) |
  | GRIP · TYRES · ENERGY | Friction circle, per-tyre grip use, forces and loads, power, battery, range, consumption and road-test results |
  | Dock (bottom) | P R N D · Dry / Wet / Snow / Ice · ABS · TCS · View · Forces · Touch · Slow-mo · Pause · Reset · Keys |

- **Keyboard:** W/↑ accelerate · S/↓ brake · A D/← → steer · Space full brake · 1–4 = P R N D · E autopilot · Q park · X emergency stop · , . indicators · / hazards · N headlights · K main beam · hold J flash · Y traffic · L lidar · O touch controls · G surface · B ABS · T TCS · C camera · F forces · M slow motion · P pause · Backspace reset · H help · F11 full screen. Keys only drive the car while the Drive page is open and never while typing in a text field; if the window loses focus, all keys are released.
- **Touchscreen and mouse** (`TouchControls`): an on-screen steering wheel and BRAKE / ACCEL pedals, each tracking its own touch point, and tappable P R N D. A tap shifts while stopped because one finger can't hold the brake and tap at the same time; the car holds itself on the brake, as touchscreen selectors do.
- **Road tests run automatically** (`PerformanceMonitor`): a full stop from above 18 km/h is measured against *v²/(2(μ+C<sub>rr</sub>)g)*, and pulling away at full throttle times 0–100 km/h.
- **3D:**
  - The car is drawn in code (a lofted body and cabin) or, if installed, loaded from a **glTF 2.0** model file (`ui.render.gltf`, our own loader for binary `.glb` with textures). Wheels spin at their real speed, the front wheels steer with their own Ackermann angles, and the body pitches, rolls and heaves with the suspension.
  - Lamps glow: indicators, hazards, dipped and main beam, brake and reversing lights.
  - The city's buildings are extruded from their outlines; roads, paint, parks and signals are generated from the network. Each layer is one mesh, so the world is a handful of draw calls.
  - Traffic is drawn as simple grey models by type, with brake lights and indicators.
- **Cameras** (C): chase, autopilot (high behind), top-down and side.
- **Accessibility:** buttons have text, tooltips and accessible text; everything on the driver display can be done from the keyboard; status is never shown by colour alone (text such as "ABS OFF", "Fail", "PRESENT"). Inactive tell-tales are deliberately dim and do not meet text-contrast guidelines. Full validation needs testing with a screen reader and an accessibility review.
- **Developer tool** (`DevAutomation`): `-Dselfdrive.script="…"` plays a timed script (sign in, open pages, press buttons, pedals, gears, destination, autopilot, scenarios, faults, screenshots) for checking visuals without a person at the keyboard.

### 5.14 City traffic

✅ *Built* (`traffic`) · verified by `TrafficTest` and `AutopilotIntegrationTest`

Cars, SUVs, auto-rickshaws, motorbikes, buses and trucks drive around the city on the lane network (160 by default; a setting, 0–240). Each type has its own size, top speed, acceleration, braking and following gap.

- **Following:** the **Intelligent Driver Model** (Treiber, Hennecke and Helbing, 2000): *a = a<sub>max</sub> [1 − (v/v<sub>0</sub>)⁴ − (s<sup>\*</sup>/s)²]* with the wanted gap *s<sup>\*</sup> = s<sub>0</sub> + v·T + v·Δv / (2√(a·b))*. The "vehicle ahead" can be a real one, the end of a queue at a red light, or a point where the vehicle must give way.
- **Lane changes:** **MOBIL** (Kesting, Treiber and Helbing, 2007): change lane when it gains acceleration and the new follower would not have to brake hard. Traffic keeps left, so moving right to overtake needs a bigger gain than moving back. Before a junction vehicles move into a lane for their next turn.
- **Junctions:** signals are obeyed; where paths cross, the one that must give way waits.
- Every vehicle is an obstacle, so the car's sensors, autopilot, emergency braking and collisions treat traffic exactly like anything else. Vehicles leave at the map edge and new ones appear out of the car's sight.

### 5.15 Automatic parking

✅ *Built* (`autopilot.ParkingPlanner`, `autopilot.ParkingController`) · verified by `ParkingTest`

Press **Park** (or Q) below 20 km/h: the car looks for a free space on its left, either **along the kerb** (parallel) or a **bay** (reversing in), and parks by itself. The proving ground has a car park with bays and kerb spaces for trying it.

- **Planning:** paths are straight lines and circular arcs for the rear axle, the point a car turns about (kinematic bicycle model: driving *s* with curvature *k = tan δ / L* turns the car by *k·s*). Parallel parking is the classic two-arc manoeuvre: with turning radius *R*, reversing through angle *θ* and back moves the car sideways by *2R(1 − cos θ)*, so *θ* follows from the distance to the space. A space is free when no wall and nothing the sensors see overlaps it.
- **Driving the plan:** each arc's own steering angle (feed-forward) plus a correction towards the path, with Drive/Reverse changes where the direction changes. The centre parking sensors end a move early if something comes within 0.35 m, and the car pauses if anything moves close by.
- **Measured:** parks 0.07 m from the target along the space, 0.04 m across, less than 1° off.

### 5.16 Lights

✅ *Built* (`vehicle.Lights`) · verified by `LightsTest`

Indicators (90 flashes a minute, cancelling themselves after a turn), hazards, headlights Off / Auto / On (Auto switches on at dusk, from the simulated clock), main beam (only with the headlights on) and flash, brake lights (also under strong regeneration, as on electric cars) and reversing lights, following UN ECE R48. The autopilot indicates by itself; emergency stops switch on the hazards.

---

## 6. Build, run, test, package

| Task | Command | IntelliJ |
|---|---|---|
| Run the app | `.\mvnw.cmd javafx:run` | **Run App** |
| Build + all tests | `.\mvnw.cmd verify` | **Build and Test** |
| Debug | — | **Run Main (direct)** with 🐞 |
| Check a new PC | `scripts\check-environment.cmd -Build` | — |
| Package for Windows | `powershell -ExecutionPolicy Bypass -File scripts\windows\package.ps1` | — |

**Build safeguards:** compiler warnings are enabled (`-Xlint:all`), and the Maven Enforcer plugin rejects Java older than 21 or Maven older than 3.9 with a readable message.

**Packaging** (`scripts\windows\package.ps1`): builds the app, works out the Java modules it needs with `jdeps`, and runs `jpackage` to make `dist\SelfDrivingCarControlSystem\` with `SelfDrivingCarControlSystem.exe` and its own trimmed Java runtime (about 160 MB with the car model), so the target PC needs nothing installed. Copy the whole folder. Add `-SkipTests` to skip the tests. The car model in `assets\models\car` is included if present. A classic installer (`.msi`) would also need the WiX Toolset on the build PC; that is optional.

**Tests** (130 in 21 classes):

| Test class | Tests | Covers |
|---|---:|---|
| `TireModelTest` | 6 | Free rolling, peak = μ·load, locked-wheel sliding grip, lateral force direction, friction circle |
| `VehicleModelTest` | 19 | Braking on all surfaces vs theory, ABS vs locked wheels, 0–100, top speed, standstill, turning radius, consumption, weight transfer, regen on ice, abuse test |
| `SimulationTest` | 7 | The loop as the UI uses it: gear rules, touch controls, road tests, reset, trip and alert events |
| `GearSelectorTest` | 5 | Every gear rule |
| `LightsTest` | 6 | Indicators at 90 a minute and cancelling after the turn, hazards, automatic headlights and main beam, brake and reversing lights, autopilot indicating |
| `ProvingGroundTest` | 4 | Polyline offset and dashes, circuit length, start position |
| `NavigationTest` | 11 | Map to lane network, left-hand lanes, turn lanes, signal phases never clash, A\* equals Dijkstra, no U-turns, detours, route geometry, cornering speeds, progress tracking |
| `SensorTest` | 6 | Ray vs box, overlap, lidar and radar ranges and closing speed, ultrasonic and failures, collisions |
| `SafetyControllerTest` | 6 | Warning then braking as TTC drops, crossing pedestrian, holding the brake, grip, reaction allowance setting, PID |
| `TrafficTest` | 2 | Three minutes of city traffic with no collisions or red lights run; traffic stops for a pedestrian |
| `AutopilotIntegrationTest` | 10 | Full drives: destination and trip record, lanes and limits, pedestrian, stopped car, overtaking, closed road, traffic, override, emergency stop |
| `ParkingTest` | 3 | The two-arc geometry, parallel parking between parked cars, reversing into a bay |
| `GltfModelTest` | 3 | Reading meshes, nodes and materials from a `.glb`, rejecting other files, the JSON parser |
| `SystemTestsTest` | 4 | A healthy car passes; each fault fails its test; diagnostics find and clear faults; the update lock |
| `PasswordHasherTest` | 7 | Hash and verify, salts, stored iterations, damaged hashes, password rules, roles and permissions |
| `DatabaseTest` | 7 | Migrations run once, statement splitting, every repository against in-memory H2 |
| `AuthServiceTest` | 6 | Sign-in, same answer for unknown users, lock-out and unlock, security alerts, deactivated accounts, wiping |
| `UserServiceTest` | 4 | Permissions (a driver cannot manage users), validation, last-admin rule, passwords |
| `SettingsServiceTest` | 3 | Defaults, audited changes, permissions, validation |
| `ApplicationContextTest` | 4 | Demo accounts, settings reaching the car, trips and alerts saved with the right users, security alerts |
| `MaintenanceAndUpdatesTest` | 7 | Issue workflow, reopening, manual issues, permissions, test runs, updates in order, checksum failure and roll back, overview |

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
├── assets/models/car/           optional glTF car model (not in the repository)
├── scripts/                     environment checker; windows\package.ps1
└── src/
    ├── main/java/com/selfdriving/
    │   ├── Main.java            entry point
    │   ├── app/                 JavaFX start-up, developer scripts
    │   ├── physics/             vehicle dynamics
    │   ├── vehicle/             VehicleState, gears, modes, lights, driver controls
    │   ├── world/               proving ground, city from the map, lane network, buildings
    │   ├── traffic/             other vehicles (IDM, MOBIL)
    │   ├── navigation/          road graph, A*, Dijkstra, routes, progress
    │   ├── sensors/             lidar, radar, ultrasonic, perception
    │   ├── autopilot/           speed and steering, junctions, lane changes, parking, safety controller
    │   ├── simulation/          loop, snapshots, scenarios, collisions, system tests
    │   ├── diagnostics/         fault codes, diagnostic reports
    │   ├── alerts/              alert bus
    │   ├── auth/                roles, access levels, permissions, password hashing
    │   ├── persistence/         H2, migrations, repositories
    │   ├── service/             sign-in, users, settings, history, maintenance, updates, recorder
    │   ├── tools/               map importer (developer tool)
    │   ├── util/                JSON parser
    │   └── ui/                  shell, login, driver, admin, technician, common pages, render (3D, glTF)
    ├── main/resources/          theme.css, db/ (SQL migrations), world/maps/ (city data)
    └── test/java/               JUnit tests
```

---

## 8. Status

| Stage | Scope | Status |
|:---:|---|:---:|
| 0 | Project foundation: build, package layout, Git, setup guide, theme, window | ✅ Done |
| 1 | Physics engine, drivable 3D car, Tesla-style driver screen, keyboard driving, road tests | ✅ Done |
| 2 | Road graph, A\*, sensors, autopilot, safety controller, alerts, scenarios, touch controls | ✅ Done |
| 2b | Real Bengaluru map with lanes and signals, city traffic, junctions and overtaking, lights, glTF car model, automatic parking | ✅ Done |
| 3 | Sign-in, roles and access levels, passwords, H2 schema and repositories, trips and alerts saved, trip history, user management | ✅ Done |
| 4 | Admin and Technician dashboards, settings, OTA updates, performance graphs, diagnostics and faults, issues, system tests, maintenance log, packaging | ✅ Done (130 tests) |

**Known limits:** the world is flat (no hills); traffic has no pedestrians walking by themselves (only the test scenario); the update server is simulated inside the app; the car model file is not shipped in the repository.

---

## 9. Glossary

| Term | Meaning |
|---|---|
| **ABS** | Anti-lock Braking System: stops wheels locking so the car can still steer while braking hard |
| **A\*** | Shortest-path algorithm that uses a distance estimate to search faster than Dijkstra |
| **AEB** | Automatic Emergency Braking |
| **Audit log** | Record of security-relevant actions: who did what and when |
| **Checksum (SHA-256)** | A fingerprint of a file; any change to the file changes it, so a damaged download is detected |
| **DTC** | Diagnostic Trouble Code, e.g. C1020 |
| **ETA** | Estimated Time of Arrival |
| **FCW** | Forward Collision Warning |
| **Fixed timestep** | Physics always advances by the same small time step, for stable, repeatable results |
| **glTF** | A standard file format for 3D models ("the JPEG of 3D"); `.glb` is its single-file binary form |
| **IDM** | Intelligent Driver Model: a car-following model giving realistic acceleration and braking in traffic |
| **JDBC** | Java's standard API for talking to SQL databases |
| **Lidar** | Laser sensor that measures distances all around the car |
| **Migration** | A versioned script that changes the database schema, run once |
| **MOBIL** | "Minimising Overall Braking Induced by Lane changes": a rule for when a vehicle changes lane |
| **ODbL** | Open Database Licence, under which OpenStreetMap data may be used with attribution |
| **OTA** | Over-The-Air software update |
| **Pacejka magic formula** | Widely used empirical formula for tyre force versus slip |
| **PBKDF2** | Password-Based Key Derivation Function 2: a deliberately slow, salted hash for storing passwords |
| **PID** | Proportional-Integral-Derivative controller, a classic feedback controller |
| **Pure pursuit** | Steering method that aims at a point a set distance ahead on the path |
| **Radar** | Radio sensor that measures the distance and closing speed of objects ahead |
| **Regenerative braking** | Using the electric motor as a generator to slow the car and recharge the battery |
| **Salt** | Random bytes mixed into each password before hashing, so equal passwords get different hashes |
| **Separating axis theorem** | Two convex shapes don't overlap if some line exists onto which their shadows don't overlap; used for collision checks |
| **Slip angle / slip ratio** | How much a tyre slides sideways / spins faster or slower than the road |
| **TTC** | Time To Collision |
| **Ultrasonic sensor** | Short-range sound sensor around the bumpers, used for parking distances |
| **VehicleState** | The one object holding the car's current state, read by everything else |
