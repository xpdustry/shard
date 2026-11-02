package com.xpdustry.shard.tools.wirelift;

import java.util.*;

/** A small line diff for the stale-file checks: the lines only one side has, with their line numbers. */
final class Diff{
    /** At most this many differing lines are printed. */
    private static final int LIMIT = 60;

    /** Lines of {@code before} missing from {@code after} as {@code -}, the reverse as {@code +}, from a longest common subsequence. */
    static String lines(String before, String after){
        List<String> a = before.lines().toList(), b = after.lines().toList();
        int n = a.size(), m = b.size();
        // trim the common head and tail, so the quadratic table only covers what changed
        int head = 0;
        while(head < n && head < m && a.get(head).equals(b.get(head))) head++;
        int tail = 0;
        while(tail < n - head && tail < m - head && a.get(n - 1 - tail).equals(b.get(m - 1 - tail))) tail++;
        List<String> x = a.subList(head, n - tail), y = b.subList(head, m - tail);
        if((long)x.size() * y.size() > 4_000_000L){
            return "  (" + x.size() + " lines differ from " + y.size() + " lines, from line " + (head + 1) + ")\n";
        }
        int[][] common = new int[x.size() + 1][y.size() + 1];
        for(int i = x.size() - 1; i >= 0; i--){
            for(int j = y.size() - 1; j >= 0; j--){
                common[i][j] = x.get(i).equals(y.get(j)) ? common[i + 1][j + 1] + 1 : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        StringBuilder out = new StringBuilder();
        int i = 0, j = 0, shown = 0;
        while((i < x.size() || j < y.size()) && shown < LIMIT){
            if(i < x.size() && j < y.size() && x.get(i).equals(y.get(j))){
                i++;
                j++;
            }else if(j < y.size() && (i == x.size() || common[i][j + 1] >= common[i + 1][j])){
                out.append(String.format("+%5d %s%n", head + j + 1, y.get(j++)));
                shown++;
            }else{
                out.append(String.format("-%5d %s%n", head + i + 1, x.get(i++)));
                shown++;
            }
        }
        if(i < x.size() || j < y.size()) out.append("  ...\n");
        return out.toString();
    }

    private Diff(){}
}
