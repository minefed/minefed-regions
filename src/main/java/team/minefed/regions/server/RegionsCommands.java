package team.minefed.regions.server;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import team.minefed.regions.RegionsMod;
import team.minefed.regions.model.Region;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class RegionsCommands {
    private RegionsCommands() { }
    @FunctionalInterface private interface Action { int run(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException; }
    private static int safely(CommandContext<ServerCommandSource> ctx, Action action) throws CommandSyntaxException {
        if (RegionsMod.controller() == null) { ctx.getSource().sendError(Text.literal("행정구역 설정을 불러오지 못했습니다. 서버 로그와 설정을 확인하세요.")); return 0; }
        try { return action.run(ctx); }
        catch (IllegalArgumentException exception) { ctx.getSource().sendError(Text.literal(exception.getMessage())); return 0; }
    }
    private static RegionController control() { return RegionsMod.controller(); }
    private static String word(CommandContext<ServerCommandSource> ctx,String name) { return StringArgumentType.getString(ctx,name); }
    private static int say(ServerCommandSource source,String message) { source.sendFeedback(() -> Text.literal(message),false); return 1; }
    private static RequiredArgumentBuilder<ServerCommandSource,String> regionArg() {
        return argument("region",StringArgumentType.word()).suggests((ctx,builder) -> {
            if (control()!=null) for (Region r : control().snapshot().regions())
                if (r.id().startsWith(builder.getRemaining())) builder.suggest(r.id(),Text.literal(control().snapshot().address(r)));
            if ("here".startsWith(builder.getRemaining())) builder.suggest("here");
            return builder.buildFuture();
        });
    }
    private static RequiredArgumentBuilder<ServerCommandSource,String> playerArg() {
        return argument("player",StringArgumentType.word()).suggests((ctx,builder) ->
                CommandSource.suggestMatching(ctx.getSource().getServer().getPlayerManager().getPlayerNames(),builder));
    }
    private static Region target(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        String ref = word(ctx,"region");
        Region region;
        if (ref.equals("here")) {
            ServerPlayerEntity player=overworld(ctx); region=control().snapshot().locate(player.getX(),player.getZ());
        } else region=control().snapshot().find(ref);
        if(region==null) throw new IllegalArgumentException("지역을 찾을 수 없습니다. /region list 로 UUID를 확인하세요.");
        return region;
    }
    private static ServerPlayerEntity overworld(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player=ctx.getSource().getPlayerOrThrow();
        if (!player.getWorld().getRegistryKey().equals(World.OVERWORLD)) throw new IllegalArgumentException("일반 월드에서만 사용할 수 있습니다.");
        return player;
    }
    private static Selection selection(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        if(!control().manager(ctx.getSource())) throw new IllegalArgumentException("행정구역 관리 권한이 필요합니다.");
        return control().selection(overworld(ctx));
    }
    private static JsonObject operation(String name) { JsonObject body=new JsonObject(); body.addProperty("operation",name); return body; }
    private static JsonObject operation(CommandContext<ServerCommandSource> ctx,String name) throws CommandSyntaxException {
        JsonObject body=operation(name); body.addProperty("regionId",target(ctx).id()); return body;
    }
    private static String identity(CommandContext<ServerCommandSource> ctx) {
        String input=word(ctx,"player");
        try { return UUID.fromString(input).toString(); }
        catch(IllegalArgumentException ignored) {
            var player=ctx.getSource().getServer().getPlayerManager().getPlayer(input);
            if(player!=null) return player.getUuidAsString();
            throw new IllegalArgumentException("접속 중인 닉네임 또는 Minecraft UUID를 입력하세요.");
        }
    }
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var root=literal("region").executes(ctx->safely(ctx,c->say(c.getSource(),
                "/region status | list [페이지] | info <UUID|here> | wand | select\n"
                +"create <상위 UUID|root> <이름> | rename <UUID|here> <이름> | boundary <UUID|here>\n"
                +"move <UUID|here> <상위 UUID|root> | delete <UUID|here> confirm | display <UUID|here> <단계|auto>\n"
                +"manager grant|revoke <UUID|here> <닉네임|UUID> | admin grant|revoke <닉네임|UUID> | history | undo <이력 UUID>")));
        root.then(literal("status").executes(ctx->safely(ctx,c->say(c.getSource(),
                "API " +(control().online()?"연결됨":"연결 끊김 · 조회만 가능")+" · 버전 "+control().snapshot().revision()+" · 지역 "+control().snapshot().regions().size()))));
        root.then(literal("list").executes(ctx->safely(ctx,c->list(c,1)))
                .then(argument("page",IntegerArgumentType.integer(1)).executes(ctx->safely(ctx,c->list(c,IntegerArgumentType.getInteger(c,"page"))))));
        root.then(literal("info").then(regionArg().executes(ctx->safely(ctx,c->{
            Region r=target(c); return say(c.getSource(),control().snapshot().address(r)+"\nUUID: "+r.id()
                    +"\n상위: "+(r.parentId()==null?"국가":r.parentId())+" · 지도 단계: "+(r.displayLevel()==null?"자동":r.displayLevel()));
        }))));
        root.then(literal("wand").executes(ctx->safely(ctx,c->{
            selection(c); ItemStack wand=new ItemStack(Items.STICK); wand.getOrCreateNbt().putBoolean(RegionsMod.WAND_TAG,true);
            wand.setCustomName(Text.literal("행정구역 경계 도구"));
            if(!overworld(c).giveItemStack(wand)) throw new IllegalArgumentException("인벤토리에 빈 공간이 필요합니다.");
            return say(c.getSource(),"블록 우클릭: 지점 추가 / 웅크려 우클릭: 마지막 지점 취소. 좌표는 블록의 X/Z 모서리입니다.");
        })));
        root.then(selectionCommands());
        root.then(literal("create").then(argument("parent",StringArgumentType.word())
                .then(argument("name",StringArgumentType.greedyString()).executes(ctx->safely(ctx,c->{
                    JsonObject body=operation("create"); body.addProperty("regionId",UUID.randomUUID().toString());
                    setParent(body,word(c,"parent")); body.addProperty("name",word(c,"name"));
                    body.add("geometry",RegionApi.JSON.toJsonTree(selection(c).geometry()));
                    body.addProperty("expectedRevision",selection(c).baseRevision());
                    return control().mutate(c.getSource(),body);
                })))));
        root.then(literal("rename").then(regionArg().then(argument("name",StringArgumentType.greedyString()).executes(ctx->safely(ctx,c->{
            JsonObject body=operation(c,"rename"); body.addProperty("name",word(c,"name")); return control().mutate(c.getSource(),body);
        })))));
        root.then(literal("boundary").then(regionArg().executes(ctx->safely(ctx,c->{
            JsonObject body=operation(c,"geometry"); body.add("geometry",RegionApi.JSON.toJsonTree(selection(c).geometry()));
            body.addProperty("expectedRevision",selection(c).baseRevision());
            return control().mutate(c.getSource(),body);
        }))));
        root.then(literal("move").then(regionArg().then(argument("parent",StringArgumentType.word()).executes(ctx->safely(ctx,c->{
            JsonObject body=operation(c,"reparent"); setParent(body,word(c,"parent")); return control().mutate(c.getSource(),body);
        })))));
        root.then(literal("delete").then(regionArg().then(literal("confirm").executes(ctx->safely(ctx,c->control().mutate(c.getSource(),operation(c,"delete")))))));
        root.then(literal("display").then(regionArg()
                .then(literal("auto").executes(ctx->safely(ctx,c->{JsonObject body=operation(c,"display");body.add("displayLevel",JsonNull.INSTANCE);return control().mutate(c.getSource(),body);})))
                .then(argument("level",IntegerArgumentType.integer(0,63)).executes(ctx->safely(ctx,c->{JsonObject body=operation(c,"display");body.addProperty("displayLevel",IntegerArgumentType.getInteger(c,"level"));return control().mutate(c.getSource(),body);})))));
        var managers=literal("manager"); var admins=literal("admin");
        for(String action:List.of("grant","revoke")) {
            managers.then(literal(action).then(regionArg().then(playerArg().executes(ctx->safely(ctx,c->{
                JsonObject body=operation(c,action);body.addProperty("managerUuid",identity(c));return control().mutate(c.getSource(),body);
            })))));
            admins.then(literal(action).then(playerArg().executes(ctx->safely(ctx,c->{
                JsonObject body=operation("admin-"+action);body.addProperty("managerUuid",identity(c));return control().mutate(c.getSource(),body);
            }))));
        }
        root.then(managers).then(admins);
        root.then(literal("history").executes(ctx->safely(ctx,c->control().history(c.getSource(),0)))
                .then(argument("beforeRevision",LongArgumentType.longArg(1)).executes(ctx->safely(ctx,c->control().history(c.getSource(),LongArgumentType.getLong(c,"beforeRevision"))))));
        root.then(literal("undo").then(argument("history",StringArgumentType.word()).executes(ctx->safely(ctx,c->{
            JsonObject body=operation("undo");body.addProperty("historyId",UUID.fromString(word(c,"history")).toString());return control().mutate(c.getSource(),body);
        }))));
        dispatcher.register(root);
    }
    private static void setParent(JsonObject body,String parent) {
        if(parent.equals("root")) body.add("parentId",JsonNull.INSTANCE);
        else {
            if(control().snapshot().find(parent)==null) throw new IllegalArgumentException("상위 지역 UUID를 찾을 수 없습니다.");
            body.addProperty("parentId",parent);
        }
    }
    private static int list(CommandContext<ServerCommandSource> ctx,int page) {
        List<Region> regions=control().snapshot().regions().stream().sorted(Comparator.comparing(r->control().snapshot().address(r))).toList();
        int pages=Math.max(1,(regions.size()+14)/15);
        if(page>pages) throw new IllegalArgumentException("페이지는 1부터 "+pages+"까지입니다.");
        StringBuilder lines=new StringBuilder("행정구역 ").append(page).append('/').append(pages);
        for(int i=(page-1)*15;i<Math.min(regions.size(),page*15);i++) {
            Region r=regions.get(i);lines.append('\n').append(r.id()).append(" · ").append(control().snapshot().address(r));
        }
        return say(ctx.getSource(),lines.toString());
    }
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> selectionCommands() {
        var select=literal("select").executes(ctx->safely(ctx,c->say(c.getSource(),
                "select clear | point [x z] | undo | part | hole | focus <영역번호> <고리번호> | drop\n"
                +"load <UUID|here> | vertex <번호> <x> <z> | remove <번호> | rebase | show")));
        select.then(literal("rebase").executes(ctx->safely(ctx,c->{
            selection(c).rebase(control().snapshot().revision());
            return say(c.getSource(),"초안의 기준 버전을 갱신했습니다. 현재 선택한 경계로 저장할 수 있습니다.");
        })));
        select.then(literal("clear").executes(ctx->safely(ctx,c->{selection(c).clear();return say(c.getSource(),"선택을 비웠습니다.");})));
        select.then(literal("point").executes(ctx->safely(ctx,c->{var player=overworld(c);selection(c).point(player.getBlockX(),player.getBlockZ());return say(c.getSource(),selection(c).description());}))
                .then(argument("x",DoubleArgumentType.doubleArg()).then(argument("z",DoubleArgumentType.doubleArg()).executes(ctx->safely(ctx,c->{
                    selection(c).point(DoubleArgumentType.getDouble(c,"x"),DoubleArgumentType.getDouble(c,"z"));return say(c.getSource(),selection(c).description());
                })))));
        select.then(literal("undo").executes(ctx->safely(ctx,c->{selection(c).undo();return say(c.getSource(),selection(c).description());})));
        select.then(literal("part").executes(ctx->safely(ctx,c->{selection(c).newPart();return say(c.getSource(),"새 분리 영역의 바깥 고리를 선택하세요.");})));
        select.then(literal("hole").executes(ctx->safely(ctx,c->{selection(c).hole();return say(c.getSource(),"현재 영역에서 제외할 내부 고리를 선택하세요.");})));
        select.then(literal("drop").executes(ctx->safely(ctx,c->{selection(c).removeRing();return say(c.getSource(),selection(c).description());})));
        select.then(literal("focus").then(argument("part",IntegerArgumentType.integer(1)).then(argument("ring",IntegerArgumentType.integer(1)).executes(ctx->safely(ctx,c->{
            selection(c).focus(IntegerArgumentType.getInteger(c,"part"),IntegerArgumentType.getInteger(c,"ring"));return say(c.getSource(),selection(c).description());
        })))));
        select.then(literal("load").then(regionArg().executes(ctx->safely(ctx,c->{selection(c).load(target(c).geometry());return say(c.getSource(),selection(c).description());}))));
        select.then(literal("vertex").then(argument("index",IntegerArgumentType.integer(1)).then(argument("x",DoubleArgumentType.doubleArg()).then(argument("z",DoubleArgumentType.doubleArg()).executes(ctx->safely(ctx,c->{
            selection(c).vertex(IntegerArgumentType.getInteger(c,"index"),DoubleArgumentType.getDouble(c,"x"),DoubleArgumentType.getDouble(c,"z"));return say(c.getSource(),selection(c).description());
        }))))));
        select.then(literal("remove").then(argument("index",IntegerArgumentType.integer(1)).executes(ctx->safely(ctx,c->{selection(c).removeVertex(IntegerArgumentType.getInteger(c,"index"));return say(c.getSource(),selection(c).description());}))));
        select.then(literal("show").executes(ctx->safely(ctx,c->{
            Selection draft=selection(c); ServerPlayerEntity player=overworld(c); List<double[]> points=draft.current(); int emitted=0;
            for(int i=0;i<points.size() && emitted<400;i++) {
                double[] a=points.get(i),b=points.get((i+1)%points.size());
                int samples=(int)Math.min(40,Math.ceil(Math.hypot(a[0]-b[0],a[1]-b[1]))+1);
                for(int j=0;j<samples && emitted++<400;j++) {
                    double fraction=j/(double)samples;
                    player.getServerWorld().spawnParticles(player,ParticleTypes.END_ROD,false,
                            a[0]+(b[0]-a[0])*fraction,player.getY()+0.5,a[1]+(b[1]-a[1])*fraction,1,0,0,0,0);
                }
            }
            return say(c.getSource(),draft.description()+"\n현재 고리를 현재 높이의 입자로 표시했습니다.");
        })));
        return select;
    }
}
