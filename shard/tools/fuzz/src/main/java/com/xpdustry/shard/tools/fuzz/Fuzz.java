package com.xpdustry.shard.tools.fuzz;

/** {@code shard-fuzz}: runs one {@link Campaign} and exits 0 when no invariant broke, 1 when one did, 2 on bad arguments. */
public final class Fuzz{
    public static void main(String[] args) throws Exception{
        FuzzConfig config;
        try{
            config = FuzzConfig.parse(args);
        }catch(IllegalArgumentException e){
            System.err.println(e.getMessage() + "\n" + FuzzConfig.USAGE);
            System.exit(2);
            return;
        }
        Campaign.Result result;
        try(var campaign = new Campaign(config)){
            result = campaign.run();
        }
        System.out.print(result.report());
        System.out.flush();
        // the server's virtual and platform threads are closed; exit for any the JDK keeps
        System.exit(result.ok() ? 0 : 1);
    }

    private Fuzz(){
    }
}
