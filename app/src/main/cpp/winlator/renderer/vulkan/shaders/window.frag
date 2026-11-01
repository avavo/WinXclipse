#version 450
layout(set=0,binding=0) uniform sampler2D image;
layout(push_constant) uniform Window { vec4 rect; int swapRB; int alpha; } pc;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
void main() {
    color = texture(image,uv);
    if(pc.swapRB!=0) color.rb=color.br;
    if(pc.alpha==0) color.a=1;
}
