package fi.alavesa.backpacks.model;

/** Small 3x3 / 4x4 matrix helpers for the converter (row-major double arrays, degrees in, degrees out). */
final class Mat {
    private Mat() { }

    static double[][] identity() { return new double[][] {{1,0,0,0},{0,1,0,0},{0,0,1,0},{0,0,0,1}}; }
    static int[][] identityPerm() { return new int[][] {{1,0,0},{0,1,0},{0,0,1}}; }
    static double[][] translate(double x, double y, double z) { double[][] m = identity(); m[0][3] = x; m[1][3] = y; m[2][3] = z; return m; }

    /** Blockbench applies group/cube rotation as Z, then Y, then X (extrinsic X first). */
    static double[][] rotZYX(double[] deg) {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = {{1,0,0,0},{0,Math.cos(x),-Math.sin(x),0},{0,Math.sin(x),Math.cos(x),0},{0,0,0,1}};
        double[][] ry = {{Math.cos(y),0,Math.sin(y),0},{0,1,0,0},{-Math.sin(y),0,Math.cos(y),0},{0,0,0,1}};
        double[][] rz = {{Math.cos(z),-Math.sin(z),0,0},{Math.sin(z),Math.cos(z),0,0},{0,0,1,0},{0,0,0,1}};
        return mul(mul(rz, ry), rx);
    }

    static double[][] rot3(double[] deg) {
        double[][] m = rotZYX(deg);
        return new double[][] {{m[0][0], m[0][1], m[0][2]}, {m[1][0], m[1][1], m[1][2]}, {m[2][0], m[2][1], m[2][2]}};
    }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double s = 0; for (int k = 0; k < 4; k++) s += a[i][k] * b[k][j]; r[i][j] = s; }
        return r;
    }

    static double[][] mul3(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { double x = 0; for (int k = 0; k < 3; k++) x += a[i][k] * b[k][j]; r[i][j] = x; }
        return r;
    }

    static int[][] mul3i(int[][] a, int[][] b) {
        int[][] r = new int[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { int x = 0; for (int k = 0; k < 3; k++) x += a[i][k] * b[k][j]; r[i][j] = x; }
        return r;
    }

    static double[][] transpose3(int[][] p) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = p[j][i];
        return r;
    }

    static double[] apply(double[][] m, double[] v) {
        return new double[] {m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2]+m[0][3], m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2]+m[1][3], m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2]+m[2][3]};
    }

    /** The signed axis permutation (a rotation by multiples of 90 degrees) nearest to R; identity if none is close. */
    static int[][] nearestPerm(double[][] R) {
        int[][] P = new int[3][3];
        boolean[] usedCol = new boolean[3];
        for (int i = 0; i < 3; i++) {
            int bj = 0;
            for (int j = 1; j < 3; j++) if (Math.abs(R[i][j]) > Math.abs(R[i][bj])) bj = j;
            if (usedCol[bj] || Math.abs(R[i][bj]) < 0.5) return identityPerm();
            usedCol[bj] = true;
            P[i][bj] = R[i][bj] >= 0 ? 1 : -1;
        }
        int det = P[0][0]*(P[1][1]*P[2][2]-P[1][2]*P[2][1]) - P[0][1]*(P[1][0]*P[2][2]-P[1][2]*P[2][0]) + P[0][2]*(P[1][0]*P[2][1]-P[1][1]*P[2][0]);
        return det == 1 ? P : identityPerm();
    }

    /** Euler angles (deg) in Blockbench ZYX order for a 3x3 rotation matrix. */
    static double[] euler3(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, -m[2][0])));
        double x = Math.atan2(m[2][1], m[2][2]);
        double z = Math.atan2(m[1][0], m[0][0]);
        return new double[] {Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z)};
    }

    /** Euler angles (deg) with rotXYZ(e) == M - what Minecraft's display "rotation" expects. */
    static double[] eulerXYZ(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, m[0][2])));
        double x = Math.atan2(-m[1][2], m[2][2]);
        double z = Math.atan2(-m[0][1], m[0][0]);
        return new double[] {Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z)};
    }

    static double[][] rotXYZ(double[] deg) {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = {{1,0,0},{0,Math.cos(x),-Math.sin(x)},{0,Math.sin(x),Math.cos(x)}};
        double[][] ry = {{Math.cos(y),0,Math.sin(y)},{0,1,0},{-Math.sin(y),0,Math.cos(y)}};
        double[][] rz = {{Math.cos(z),-Math.sin(z),0},{Math.sin(z),Math.cos(z),0},{0,0,1}};
        return mul3(mul3(rx, ry), rz);
    }
}
