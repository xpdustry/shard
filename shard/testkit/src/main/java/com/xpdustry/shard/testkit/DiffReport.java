package com.xpdustry.shard.testkit;

import java.nio.file.*;
import java.util.*;

/**
 * The outcome of a {@link Differential} or an {@link Expect} check: whether the sides agreed at every checkpoint, and
 * if not, the first checkpoint where they didn't, with every leaf that differs there. {@link #text()} is what a
 * failing test prints.
 *
 * @param mode {@code trace}, {@code every 60 ticks}, {@code at the end}…
 * @param checkpoints how many checkpoints were compared, the failing one included
 * @param divergedAt the first checkpoint that failed, or -1
 * @param lastAgreed the checkpoint before it where the sides agreed, or -1 (none yet)
 * @param notes what wasn't compared and why: sections a side doesn't project, kind fields only the reference has
 * @param dumps where the failing checkpoint's projections were written, or null
 */
public record DiffReport(String scenario, String reference, String candidate, String mode, long ticks, int checkpoints,
                         long divergedAt, long lastAgreed, List<Difference> differences, List<String> notes, Path dumps,
                         double referenceSeconds, double candidateSeconds){
    /** Differences {@link #text()} lists; the rest are counted, and all are in {@link #differences()}. */
    public static final int SHOWN = 25;

    public DiffReport{
        differences = List.copyOf(differences);
        notes = List.copyOf(notes);
    }

    public boolean ok(){
        return divergedAt < 0;
    }

    /** Throws an {@link AssertionError} with {@link #text()} unless the sides agreed. */
    public DiffReport assertNoDiff(){
        if(!ok()) throw new AssertionError(text());
        return this;
    }

    /** Throws unless the first divergence is at {@code tick}: for tests that break something on purpose. */
    public DiffReport assertDivergesAt(long tick){
        if(divergedAt != tick){
            throw new AssertionError("expected the first divergence at tick " + tick + ", got " + (ok() ? "none" : "tick " + divergedAt) + "\n" + text());
        }
        return this;
    }

    /** The differences at {@code where} ({@code building(4,2).fields.amount}), for assertions. */
    public List<Difference> at(String where){
        return differences.stream().filter(d -> d.where().equals(where) || d.where().startsWith(where + ".")).toList();
    }

    public String text(){
        var out = new StringBuilder();
        out.append(ok() ? "OK" : "FAIL").append(" scenario '").append(scenario).append("': ").append(reference)
            .append(" (reference) vs ").append(candidate).append(" (candidate), ").append(mode).append(", ").append(ticks).append(" ticks");
        if(ok()){
            out.append(": ").append(checkpoints).append(checkpoints == 1 ? " checkpoint agrees" : " checkpoints agree").append('\n');
        }else{
            out.append('\n').append("  first divergence at tick ").append(divergedAt);
            out.append(lastAgreed < 0 ? " (the first checkpoint)" : " (tick " + lastAgreed + " agreed)");
            if(!mode.equals("trace") && !mode.startsWith("expectations") && divergedAt - lastAgreed > 1){
                out.append(": rerun with trace() to find the exact tick");
            }
            out.append('\n');
            table(out);
        }
        for(String note : notes) out.append("  not compared: ").append(note).append('\n');
        if(dumps != null) out.append("  dumps: ").append(dumps).append('\n');
        out.append(String.format(Locale.ROOT, "  time: %s %.2f s, %s %.2f s%n", reference, referenceSeconds, candidate, candidateSeconds));
        return out.toString();
    }

    private void table(StringBuilder out){
        var rows = new ArrayList<String[]>();
        rows.add(new String[]{"where", "", reference, candidate, "tolerance", lastAgreed < 0 ? "" : "at tick " + lastAgreed});
        for(Difference d : differences.subList(0, Math.min(SHOWN, differences.size()))){
            rows.add(new String[]{d.where(), d.label() == null ? "" : d.label(), Difference.show(d.reference()),
                Difference.show(d.candidate()), d.tolerance(), d.before() == Difference.ABSENT ? "" : Difference.show(d.before())});
        }
        int[] widths = new int[6];
        for(String[] row : rows){
            for(int i = 0; i < row.length; i++) widths[i] = Math.max(widths[i], Math.min(row[i].length(), 48));
        }
        for(String[] row : rows){
            out.append("   ");
            for(int i = 0; i < row.length; i++){
                String cell = row[i].length() > 48 ? row[i].substring(0, 45) + "..." : row[i];
                out.append(' ').append(cell).append(" ".repeat(widths[i] - cell.length())).append(i == 0 ? " " : "");
            }
            out.setLength(out.length() - countTrailing(out));
            out.append('\n');
        }
        if(differences.size() > SHOWN) out.append("    … and ").append(differences.size() - SHOWN).append(" more differences (all in the dumps)\n");
    }

    private static int countTrailing(StringBuilder out){
        int n = 0;
        while(n < out.length() && out.charAt(out.length() - 1 - n) == ' ') n++;
        return n;
    }
}
