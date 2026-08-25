package net.minecheck.common.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

class LuaYieldTest {

  @Test
  void javaFunctionCanYieldCoroutine() {
    Globals globals = JsePlatform.standardGlobals();
    final int[] ticks = {0};
    globals.set(
        "blockUntil",
        new VarArgFunction() {
          @Override
          public Varargs invoke(Varargs args) {
            int target = args.arg1().checkint();
            while (ticks[0] < target) {
              globals.yield(LuaValue.NIL);
            }
            return LuaValue.valueOf("done");
          }
        });

    LuaThread co =
        (LuaThread)
            globals
                .get("coroutine")
                .get("create")
                .call(globals.load("local r = blockUntil(3) return r", "@t.lua"));

    Varargs out = co.resume(LuaValue.NONE);
    assertEquals("suspended", co.getStatus());
    assertTrue(out.arg1().toboolean());

    ticks[0] = 1;
    out = co.resume(LuaValue.NONE);
    assertEquals("suspended", co.getStatus());

    ticks[0] = 3;
    out = co.resume(LuaValue.NONE);
    assertEquals("dead", co.getStatus());
    assertTrue(out.arg1().toboolean());
    assertEquals("done", out.arg(2).tojstring());
  }

  @Test
  void nestedYieldThroughLuaFunctionWorks() {
    Globals globals = JsePlatform.standardGlobals();
    final int[] ticks = {0};
    globals.set(
        "blockUntil",
        new VarArgFunction() {
          @Override
          public Varargs invoke(Varargs args) {
            int target = args.arg1().checkint();
            while (ticks[0] < target) {
              globals.yield(LuaValue.NIL);
            }
            return LuaValue.valueOf("done");
          }
        });

    LuaThread co =
        (LuaThread)
            globals
                .get("coroutine")
                .get("create")
                .call(
                    globals.load(
                        "function go()\n"
                            + "  local r = blockUntil(2)\n"
                            + "  return 'lua got: ' .. r\n"
                            + "end\n"
                            + "return go()\n",
                        "@n.lua"));

    Varargs out = co.resume(LuaValue.NONE);
    assertEquals("suspended", co.getStatus());
    assertTrue(out.arg1().toboolean());
    ticks[0] = 1;
    co.resume(LuaValue.NONE);
    ticks[0] = 2;
    out = co.resume(LuaValue.NONE);
    assertEquals("dead", co.getStatus());
    assertTrue(out.arg1().toboolean());
    assertEquals("lua got: done", out.arg(2).tojstring());
  }

  @Test
  void errorsSurfaceThroughResume() {
    Globals globals = JsePlatform.standardGlobals();
    globals.set(
        "boom",
        new VarArgFunction() {
          @Override
          public Varargs invoke(Varargs args) {
            throw new LuaError("deliberate boom");
          }
        });

    LuaThread co =
        (LuaThread) globals.get("coroutine").get("create").call(globals.load("boom()", "@err.lua"));

    Varargs out = co.resume(LuaValue.NONE);
    assertFalse(out.arg1().toboolean());
    assertEquals("dead", co.getStatus());
    assertTrue(out.arg(2).tojstring().contains("deliberate boom"));
  }
}
