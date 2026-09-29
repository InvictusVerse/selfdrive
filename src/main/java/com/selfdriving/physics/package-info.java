/**
 * Vehicle dynamics engine, written from scratch.
 *
 * <p>Rigid-body car model integrated at a fixed timestep: tyre forces from a Pacejka
 * "magic formula" (slip angle / slip ratio), per-wheel spring-damper suspension with
 * load transfer, electric motor torque curve with regenerative braking, brakes with ABS,
 * traction control, aerodynamic drag, rolling resistance and battery energy use.
 */
package com.selfdriving.physics;
