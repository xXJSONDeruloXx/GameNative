package com.winlator.renderer.material;

public class ShadowMaterial extends ShaderMaterial {
    public ShadowMaterial() {
        setUniformNames("xform", "viewSize", "edgeWeights", "shadowAlpha");
    }

    @Override
    protected String getVertexShader() {
        return
                "uniform float xform[6];\n" +
                        "uniform vec2 viewSize;\n" +
                        "attribute vec2 position;\n" +
                        "varying vec2 vUV;\n" +
                        "\n" +
                        "void main() {\n" +
                        "vUV = position;\n" +
                        "vec2 transformedPos = applyXForm(position, xform);\n" +
                        "gl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);\n" +
                        "}";
    }

    @Override
    protected String getFragmentShader() {
        return
                "precision mediump float;\n" +
                        "uniform vec4 edgeWeights;\n" +
                        "uniform float shadowAlpha;\n" +
                        "varying vec2 vUV;\n" +
                        "\n" +
                        "void main() {\n" +
                        "float alpha = dot(edgeWeights, vec4(vUV.x, 1.0 - vUV.x, vUV.y, 1.0 - vUV.y)) * shadowAlpha;\n" +
                        "gl_FragColor = vec4(0.0, 0.0, 0.0, alpha);\n" +
                        "}";
    }
}
