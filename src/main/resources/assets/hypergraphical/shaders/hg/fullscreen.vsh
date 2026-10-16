#version 330 core

// Full-viewport triangle generated from gl_VertexID, so no vertex buffer is needed.
// Vertices land at (-1,-1), (3,-1), (-1,3), which covers the viewport with one triangle
// instead of two, avoiding the quad-helper waste along a diagonal seam.
//
// vUv spans [0,1] across the viewport with vUv.y = 0 at the BOTTOM. That matters: a
// framebuffer's row 0 is its bottom row, and glCopyTexSubImage2D / render-to-texture writes
// row 0 to texture row 0, which is cubemap coordinate t = 0. So vUv maps directly to (s, t).

out vec2 vUv;

void main() {
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
