package com.winlator.cmod.renderer.lsfg;

import android.opengl.GLES31;
import android.util.Log;

/** GLES 3.1 motion-estimation fallback used when the Apex native engine fails. */
final class LSFGComputeMaterial {
    private static final String TAG = "LSFGCompute";
    int programId;
    private int qualityLocation = -1;
    private String lastError = "";
    private boolean setupValidated;

    boolean use(int quality) {
        if (programId == 0) {
            programId = compileComputeShader();
            if (programId == 0) return false;
            qualityLocation = GLES31.glGetUniformLocation(programId, "quality");
        }
        GLES31.glUseProgram(programId);
        if (qualityLocation != -1) GLES31.glUniform1i(qualityLocation, quality);
        // glGetError can synchronize the mobile GPU queue. Validate the setup
        // once after compilation; LSFGEffect samples dispatch errors later.
        if (!setupValidated) {
            int error = GLES31.glGetError();
            if (error != GLES31.GL_NO_ERROR) {
                lastError = "GLES compute setup error 0x" + Integer.toHexString(error);
                Log.e(TAG, lastError);
                return false;
            }
            setupValidated = true;
        }
        return true;
    }

    private int compileComputeShader() {
        String source = "#version 310 es\n"
                + "layout(local_size_x=16, local_size_y=8, local_size_z=1) in;\n"
                // Xclipse's GLES compiler requires precision for opaque image
                // types even when the image format is explicitly declared.
                // Other drivers accepted the previous shader silently.
                + "precision highp float;\n"
                + "precision highp int;\n"
                + "precision highp sampler2D;\n"
                + "precision highp image2D;\n"
                + "uniform sampler2D currFrame;\n"
                + "uniform sampler2D prevFrame;\n"
                + "uniform sampler2D mvHistoryTexture;\n"
                + "uniform int quality;\n"
                + "layout(rgba16f, binding=0) uniform writeonly highp image2D motionVectorOutput;\n"
                + "float luma(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}\n"
                + "void main(){\n"
                + " ivec2 p=ivec2(gl_GlobalInvocationID.xy); ivec2 sz=imageSize(motionVectorOutput);\n"
                + " if(p.x>=sz.x||p.y>=sz.y)return;\n"
                + " vec2 uv=(vec2(p)+0.5)/vec2(sz), ts=1.0/vec2(sz);\n"
                + " float c=luma(textureLod(currFrame,uv,0.0).rgb);\n"
                + " float d=abs(c-luma(textureLod(prevFrame,uv,0.0).rgb));\n"
                + " if(d<0.01){imageStore(motionVectorOutput,p,vec4(0.0,0.0,1.0,1.0));return;}\n"
                + " float cut=d>0.88?1.0:0.0, best=d; vec2 mv=vec2(0.0);\n"
                + " int iterations=quality==0?2:(quality==1?3:(quality==2?4:5));\n"
                + " if(cut<0.5){for(int i=5;i>=1;i--){if(i>iterations)continue;\n"
                + "  float s=1.0;\n"
                + "  if(quality==0){if(i==2)s=4.0;}\n"
                + "  else if(quality==1){if(i==3)s=8.0;else if(i==2)s=3.0;}\n"
                + "  else if(quality==2){if(i==4)s=16.0;else if(i==3)s=6.0;else if(i==2)s=3.0;}\n"
                + "  else {if(i==5)s=20.0;else if(i==4)s=10.0;else if(i==3)s=5.0;else if(i==2)s=2.0;}\n"
                + "  vec2 o[4];o[0]=vec2(s,0);o[1]=vec2(-s,0);o[2]=vec2(0,s);o[3]=vec2(0,-s);\n"
                + "  for(int j=0;j<4;j++){vec2 candidate=mv+o[j]*ts;float sad=abs(c-luma(textureLod(prevFrame,uv+candidate,0.0).rgb));if(sad<best){best=sad;mv=candidate;}}}}\n"
                + " vec2 old=textureLod(mvHistoryTexture,uv,0.0).rg;\n"
                + " vec2 hist=textureLod(mvHistoryTexture,clamp(uv-old,0.0,1.0),0.0).rg;\n"
                + " vec2 stable=mix(hist*(1.0-cut),mv,0.8); float conf=1.0-clamp(best*4.0,0.0,1.0);\n"
                + " imageStore(motionVectorOutput,p,vec4(stable,conf,1.0));}\n";

        int shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);
        GLES31.glShaderSource(shader, source);
        GLES31.glCompileShader(shader);
        int[] status = new int[1];
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            lastError = "Compute shader compile error: " + GLES31.glGetShaderInfoLog(shader);
            Log.e(TAG, lastError);
            GLES31.glDeleteShader(shader);
            return 0;
        }
        int program = GLES31.glCreateProgram();
        GLES31.glAttachShader(program, shader);
        GLES31.glLinkProgram(program);
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0);
        GLES31.glDeleteShader(shader);
        if (status[0] == 0) {
            lastError = "Compute program link error: " + GLES31.glGetProgramInfoLog(program);
            Log.e(TAG, lastError);
            GLES31.glDeleteProgram(program);
            return 0;
        }
        lastError = "";
        return program;
    }

    String getLastError() {
        return lastError.isEmpty() ? "GLES compute backend unavailable" : lastError;
    }

    void destroy() {
        if (programId != 0) {
            GLES31.glDeleteProgram(programId);
            programId = 0;
        }
        qualityLocation = -1;
        lastError = "";
        setupValidated = false;
    }
}
