package com.winlator.cmod.renderer.material;

import android.graphics.Color;
import android.opengl.GLES20;
import android.util.Log;

import androidx.collection.ArrayMap;

public class ShaderMaterial {
    public int programId;
    private final ArrayMap<String, Integer> uniforms = new ArrayMap<>();
    private static final int UNRESOLVED = Integer.MIN_VALUE;
    private long contextEpoch = Long.MIN_VALUE;

    public void setUniformNames(String... names) {
        uniforms.clear();
        for (String name : names) uniforms.put(name, UNRESOLVED);
    }

    protected static int compileShaders(String vertexShader, String fragmentShader) {
        int beginIndex = vertexShader.indexOf("void main() {");
        vertexShader = vertexShader.substring(0, beginIndex) +
            "vec2 applyXForm(vec2 p, float xform[6]) {\n" +
                "return vec2(xform[0] * p.x + xform[2] * p.y + xform[4], xform[1] * p.x + xform[3] * p.y + xform[5]);\n" +
            "}\n" +
        vertexShader.substring(beginIndex);

        int programId = GLES20.glCreateProgram();
        int vertexShaderId = 0, fragmentShaderId = 0;
        boolean linked = false;
        try {
            int[] status = new int[1];
            vertexShaderId = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
            GLES20.glShaderSource(vertexShaderId, vertexShader);
            GLES20.glCompileShader(vertexShaderId);
            GLES20.glGetShaderiv(vertexShaderId, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) throw new RuntimeException("Could not compile vertex shader: " + GLES20.glGetShaderInfoLog(vertexShaderId));
            GLES20.glAttachShader(programId, vertexShaderId);
            fragmentShaderId = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
            GLES20.glShaderSource(fragmentShaderId, fragmentShader);
            GLES20.glCompileShader(fragmentShaderId);
            GLES20.glGetShaderiv(fragmentShaderId, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) throw new RuntimeException("Could not compile fragment shader: " + GLES20.glGetShaderInfoLog(fragmentShaderId));
            GLES20.glAttachShader(programId, fragmentShaderId);
            // All compositor quads use position. Pin it before linking so recycled
            // program names cannot leave the shared vertex attribute at an old index.
            GLES20.glBindAttribLocation(programId, 0, "position");
            GLES20.glLinkProgram(programId);
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) throw new RuntimeException("Could not link shader program: " + GLES20.glGetProgramInfoLog(programId));
            linked = true;
            return programId;
        } finally {
            if (vertexShaderId != 0) GLES20.glDeleteShader(vertexShaderId);
            if (fragmentShaderId != 0) GLES20.glDeleteShader(fragmentShaderId);
            if (!linked) GLES20.glDeleteProgram(programId);
        }
    }

    protected String getVertexShader() {
        return "";
    }

    protected String getFragmentShader() {
        return "";
    }

    public void use() {
        if (contextEpoch != com.winlator.cmod.renderer.GLContextEpoch.current()) invalidateContext();
        if (programId == 0) programId = buildProgram();
        bindProgram(programId);

        for (int i = 0; i < uniforms.size(); i++) {
            int location = uniforms.valueAt(i);
            if (location == UNRESOLVED) {
                String name = uniforms.keyAt(i);
                uniforms.put(name, locateUniform(programId, name));
            }
        }
    }

    protected int buildProgram() { return compileShaders(getVertexShader(), getFragmentShader()); }
    protected void bindProgram(int program) { GLES20.glUseProgram(program); }
    protected int locateUniform(int program, String name) { return GLES20.glGetUniformLocation(program, name); }
    protected void deleteProgram(int program) { GLES20.glDeleteProgram(program); }

    public int getUniformLocation(String name) {
        Integer location = uniforms.get(name);
        if (location == null) {
            Log.e("ShaderMaterial", "Uniform " + name + " is not registered in setUniformNames().");
            return -1;
        }
        return location == UNRESOLVED ? -1 : location;
    }


    public void destroy() {
        if (programId != 0 && contextEpoch == com.winlator.cmod.renderer.GLContextEpoch.current()) deleteProgram(programId);
        invalidateContext();
    }

    public void invalidateContext() {
        programId = 0;
        contextEpoch = com.winlator.cmod.renderer.GLContextEpoch.current();
        for (int i = 0; i < uniforms.size(); i++) uniforms.setValueAt(i, UNRESOLVED);
    }

    private boolean isRegistered(String name) {
        // Effects may set uniforms the shader does not declare (e.g. the
        // composer pushes "resolution" to every pass); skip silently instead
        // of logging an error every frame.
        return uniforms.containsKey(name);
    }

    public void setUniformVec2(String uniformName, float x, float y) {
        if (!isRegistered(uniformName)) return;
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            GLES20.glUniform2f(location, x, y);
        }
    }

    public void setUniformVec4(String uniformName, float[] values) {
        if (!isRegistered(uniformName)) return;
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            GLES20.glUniform4fv(location, 1, values, 0);
        }
    }

    public void setUniformInt(String uniformName, int value) {
        if (!isRegistered(uniformName)) return;
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            GLES20.glUniform1i(location, value);
        }
    }

    public void setUniformFloat(String name, float value) {
        if (!isRegistered(name)) return;
        int location = getUniformLocation(name);
        if (location >= 0) {
            GLES20.glUniform1f(location, value);
        }
    }


    public void setUniformFloatArray(String uniformName, float[] values) {
        if (!isRegistered(uniformName)) return;
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            GLES20.glUniform1fv(location, values.length, values, 0);
        }
    }

    public void setUniformColor(String uniformName, int color) {
        if (!isRegistered(uniformName)) return;
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            float red = Color.red(color) / 255.0f;
            float green = Color.green(color) / 255.0f;
            float blue = Color.blue(color) / 255.0f;
            GLES20.glUniform3f(location, red, green, blue);
        }
    }

    public void setUniformVec3(String uniformName, float x, float y, float z) {
        if (!isRegistered(uniformName)) return;
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            GLES20.glUniform3f(location, x, y, z);
        }
    }



}
