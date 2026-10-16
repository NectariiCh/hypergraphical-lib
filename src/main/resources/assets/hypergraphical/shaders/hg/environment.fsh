#version 330 core

// Procedural sky/sun/ground cubemap.
//
// The fallback capture strategy, used when a real level capture is not possible: an active
// Oculus shader pack (Iris is not re-entrant), Fabulous graphics (its post chain composites
// at window size and would stomp our small viewport), or a capture that threw.
//
// It reflects no actual geometry, so it will not show the wall next to your model. What it
// does give is a correctly coloured, correctly oriented environment for the time of day and
// biome, which is enough for a metal to read as metal instead of as flat grey.

#hg_include "hypergraphical:shaders/hg/ibl_common.glsl"

uniform int uFace;
uniform vec3 uSkyColor;
uniform vec3 uFogColor;
uniform vec3 uGroundColor;
uniform vec3 uSunDirection;
uniform float uSunIntensity;

in vec2 vUv;
out vec4 fragColor;

// Minecraft authors sky, fog and biome colours in gamma space, but this map is consumed as
// linear radiance (it skips the decode the LEVEL capture goes through). Decode the inputs
// here so both capture strategies hand the convolutions the same kind of numbers.
vec3 decode(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

void main() {
    vec3 dir = normalize(hgDirectionForFace(uFace, vUv));
    vec3 color;

    vec3 skyColor = decode(uSkyColor);
    vec3 fogColor = decode(uFogColor);
    vec3 groundColor = decode(uGroundColor);

    if (dir.y >= 0.0) {
        // Sky: zenith colour easing into the fog colour at the horizon. The 4th power keeps
        // the transition tight to the horizon the way vanilla's sky gradient does.
        float horizon = pow(1.0 - dir.y, 4.0);
        color = mix(skyColor, fogColor, horizon);

        float sunDot = max(dot(dir, normalize(uSunDirection)), 0.0);
        vec3 sunTint = vec3(1.0, 0.95, 0.85);
        // Disc plus a wide bloom. The disc is deliberately over-bright: it is what puts a
        // moving highlight on a smooth surface once the map is prefiltered.
        color += sunTint * pow(sunDot, 900.0) * 24.0 * uSunIntensity;
        color += sunTint * pow(sunDot, 12.0) * 0.30 * uSunIntensity;
    } else {
        // Below the horizon: fog colour fading into a dim ground bounce, so the underside of
        // a metal is dark but not a black hole.
        float depth = clamp(-dir.y * 2.5, 0.0, 1.0);
        color = mix(fogColor, groundColor, depth);
    }

    fragColor = vec4(max(color, vec3(0.0)), 1.0);
}
