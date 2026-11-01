#version 450
layout(push_constant) uniform Presentation {
    layout(offset=96) int rotation;
    layout(offset=100) int mirror;
} pc;
layout(location=0) out vec2 vUV;
void main() {
    vec2 corner=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    vUV=vec2(corner.x,1.0-corner.y);
    vec2 p=corner*2.0-1.0;
    if(pc.mirror!=0)p.x=-p.x;
    if(pc.rotation==1)p=vec2(-p.y,p.x);
    else if(pc.rotation==2)p=-p;
    else if(pc.rotation==3)p=vec2(p.y,-p.x);
    gl_Position=vec4(p,0,1);
}
