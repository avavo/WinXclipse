#version 450
layout(push_constant) uniform Window {
    vec4 rect; int swapRB; int alpha;
    layout(offset=96) int rotation;
    layout(offset=100) int mirror;
} pc;
layout(location=0) out vec2 uv;
void main() {
    const vec2 corners[6] = vec2[](vec2(0,0),vec2(1,0),vec2(0,1),vec2(0,1),vec2(1,0),vec2(1,1));
    uv = corners[gl_VertexIndex];
    vec2 p=mix(pc.rect.xy,pc.rect.zw,uv);
    if(pc.mirror!=0)p.x=-p.x;
    if(pc.rotation==1)p=vec2(-p.y,p.x);
    else if(pc.rotation==2)p=-p;
    else if(pc.rotation==3)p=vec2(p.y,-p.x);
    gl_Position = vec4(p,0,1);
}
