package net.minecheck.common.script;

import java.math.BigDecimal;
import java.util.function.DoubleBinaryOperator;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * A signed 64-bit integer that a Lua script can hold without losing precision.
 *
 * <p>LuaJ implements Lua 5.2, where every number is a double, so a value past 2^53 cannot survive a
 * round trip through one. Rather than round silently, every 64-bit integer this API hands to a
 * script — {@code reader.readLong()}, an {@code i64} packet argument, an NBT long tag — arrives as
 * one of these. It keeps the exact decimal text and converts to a number only when it is actually
 * used as one, which is the inverse of the old behavior (a plain double that had already lost the
 * value by the time the script saw it).
 *
 * <p>It is a table, so {@code type(v)} reports {@code "table"}. What it guarantees:
 *
 * <table>
 *   <tr><th>Use</th><th>Result</th></tr>
 *   <tr><td>{@code tostring(v)}, {@code v .. ""}, {@code string.format('%d', v)}</td>
 *       <td>the exact decimal text</td></tr>
 *   <tr><td>{@code v == 5}, {@code 5 == v}, {@code v < 5}, {@code 5 < v}, {@code v >= 5}</td>
 *       <td>exact comparison, in both operand orders</td></tr>
 *   <tr><td>{@code v + 1}, {@code 2 * v}, {@code -v}, {@code math.floor(v)}</td>
 *       <td>a plain number (a double) — exactness ends here, by design</td></tr>
 *   <tr><td>{@code v:eq(5)}, {@code v:toString()}, {@code v:toNumber()}</td>
 *       <td>the explicit forms, when the coercions above are not wanted</td></tr>
 * </table>
 *
 * <p>Two limits are worth knowing. {@code type()} says {@code table}, so anything that demands a
 * number of a table (concatenating through {@code %s} is fine, {@code math} and {@code
 * string.format('%d')} are fine) still works through the numeric checks below, but nothing can make
 * {@code pairs(v)} meaningful. And Lua only tries {@code __eq} when both operands are tables, so
 * {@code 5 == v} is uniformly {@code false} while {@code v == 5} compares properly; the JVM cannot
 * be made to consult this class from a numeric left operand.
 */
public final class LuaInt64 extends LuaTable {

  private static final LuaTable METATABLE = new LuaTable();
  private static final LuaTable METHODS = new LuaTable();

  static {
    METATABLE.set("__add", arithmetic("+", (a, b) -> a + b));
    METATABLE.set("__sub", arithmetic("-", (a, b) -> a - b));
    METATABLE.set("__mul", arithmetic("*", (a, b) -> a * b));
    METATABLE.set("__div", arithmetic("/", (a, b) -> a / b));
    // Lua's modulo, not Java's: a - floor(a/b)*b, so -1 % 3 is 2 rather than -1.
    METATABLE.set("__mod", arithmetic("%", (a, b) -> a - Math.floor(a / b) * b));
    METATABLE.set("__pow", arithmetic("^", Math::pow));
    METATABLE.set(
        "__unm",
        new VarArgFunction() {
          @Override
          public Varargs invoke(Varargs args) {
            return LuaValue.valueOf(-numeric("unary minus", args.arg1()));
          }
        });
    METATABLE.set(
        "__concat",
        new VarArgFunction() {
          @Override
          public Varargs invoke(Varargs args) {
            return LuaValue.valueOf(text(args.arg1()) + text(args.arg(2)));
          }
        });
    METATABLE.set(
        "__tostring",
        new VarArgFunction() {
          @Override
          public Varargs invoke(Varargs args) {
            return LuaValue.valueOf(asInt64("tostring", args.arg1()).exact);
          }
        });
    METATABLE.set(
        "__eq",
        new VarArgFunction() {
          @Override
          public Varargs invoke(Varargs args) {
            LuaValue left = args.arg1();
            LuaValue right = args.arg(2);
            return LuaValue.valueOf(left instanceof LuaInt64 a && a.eq_b(right));
          }
        });
    METATABLE.set("__index", METHODS);

    METHODS.set(
        "toString",
        new VarArgFunction() {
          @Override
          public Varargs invoke(Varargs args) {
            return LuaValue.valueOf(asInt64("toString", args.arg1()).exact);
          }
        });
    METHODS.set(
        "toNumber",
        new VarArgFunction() {
          @Override
          public Varargs invoke(Varargs args) {
            return LuaValue.valueOf(asInt64("toNumber", args.arg1()).asDouble());
          }
        });
    METHODS.set(
        "eq",
        new VarArgFunction() {
          @Override
          public Varargs invoke(Varargs args) {
            return LuaValue.valueOf(asInt64("eq", args.arg1()).eq_b(args.arg(2)));
          }
        });
  }

  /** Exact decimal text; the value's canonical form and the only thing it stores. */
  private final String exact;

  /** The value, for numeric use. Every conversion goes through this. */
  private final long value;

  private LuaInt64(long value) {
    this.value = value;
    this.exact = Long.toString(value);
    setmetatable(METATABLE);
  }

  /** Wraps an exact 64-bit value. */
  public static LuaInt64 of(long value) {
    return new LuaInt64(value);
  }

  /**
   * Parses a decimal integer.
   *
   * @throws LuaError when {@code text} is not a base-ten integer that fits in 64 signed bits
   */
  public static LuaInt64 parse(String text) {
    try {
      return new LuaInt64(Long.parseLong(text.trim()));
    } catch (NumberFormatException e) {
      throw new LuaError("int64: not a 64-bit integer: " + text);
    }
  }

  /** The exact value, as a Java long. */
  public long longValue() {
    return value;
  }

  /** The exact decimal text — never a rounded rendering. */
  public String exactText() {
    return exact;
  }

  // ------------------------------------------------------------ numeric interop

  /**
   * The value as a double. This is where exactness is traded away, so callers that need it should
   * prefer {@link #exactText()} or {@link #longValue()}.
   */
  public double asDouble() {
    return value;
  }

  @Override
  public double todouble() {
    return asDouble();
  }

  @Override
  public long tolong() {
    return value;
  }

  @Override
  public int toint() {
    return narrow("toint");
  }

  @Override
  public double checkdouble() {
    return asDouble();
  }

  @Override
  public long checklong() {
    return value;
  }

  @Override
  public int checkint() {
    return narrow("checkint");
  }

  @Override
  public double optdouble(double defval) {
    return asDouble();
  }

  @Override
  public long optlong(long defval) {
    return value;
  }

  @Override
  public int optint(int defval) {
    return narrow("optint");
  }

  /**
   * What libraries that insist on a real number ({@code string.format}, {@code math}) receive from
   * {@link #checknumber}. It is a {@link LuaNumber}, so those code paths behave, but it hands back
   * the <em>exact</em> long from {@code tolong()} and refuses a 32-bit {@code toint()} instead of
   * truncating. Only {@code todouble()} trades exactness, which is the documented conversion.
   */
  private final class Coerced extends LuaNumber {
    @Override
    public double todouble() {
      return asDouble();
    }

    @Override
    public long tolong() {
      return value;
    }

    @Override
    public int toint() {
      return narrow("toint");
    }

    @Override
    public String tojstring() {
      return exact;
    }
  }

  @Override
  public LuaNumber checknumber() {
    return new Coerced();
  }

  @Override
  public LuaNumber checknumber(String errmsg) {
    return new Coerced();
  }

  @Override
  public LuaNumber optnumber(LuaNumber defval) {
    return new Coerced();
  }

  /**
   * A 64-bit value that does not fit in 32 bits is refused rather than truncated: silently keeping
   * the low bits is the class of bug this type exists to remove.
   */
  private int narrow(String how) {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new LuaError(
          "int64: " + exact + " does not fit in a 32-bit int, so " + how + " cannot return it");
    }
    return (int) value;
  }

  // ------------------------------------------------------------ comparisons

  @Override
  public boolean eq_b(LuaValue val) {
    if (val instanceof LuaInt64 other) {
      return value == other.value;
    }
    // A plain number is compared against the exact value, not against its own rounded self.
    return val.isnumber() && compareToNumber(val.todouble()) == 0;
  }

  @Override
  public boolean lt_b(LuaValue rhs) {
    return compareWith(rhs) < 0;
  }

  @Override
  public boolean lt_b(int rhs) {
    return value < rhs;
  }

  @Override
  public boolean lt_b(double rhs) {
    return compareToNumber(rhs) < 0;
  }

  @Override
  public boolean gt_b(LuaValue rhs) {
    return compareWith(rhs) > 0;
  }

  @Override
  public boolean gt_b(int rhs) {
    return value > rhs;
  }

  @Override
  public boolean gt_b(double rhs) {
    return compareToNumber(rhs) > 0;
  }

  @Override
  public boolean lteq_b(LuaValue rhs) {
    return compareWith(rhs) <= 0;
  }

  @Override
  public boolean lteq_b(int rhs) {
    return value <= rhs;
  }

  @Override
  public boolean lteq_b(double rhs) {
    return compareToNumber(rhs) <= 0;
  }

  @Override
  public boolean gteq_b(LuaValue rhs) {
    return compareWith(rhs) >= 0;
  }

  @Override
  public boolean gteq_b(int rhs) {
    return value >= rhs;
  }

  @Override
  public boolean gteq_b(double rhs) {
    return compareToNumber(rhs) >= 0;
  }

  @Override
  public LuaValue lt(LuaValue rhs) {
    return LuaValue.valueOf(lt_b(rhs));
  }

  @Override
  public LuaValue gt(LuaValue rhs) {
    return LuaValue.valueOf(gt_b(rhs));
  }

  @Override
  public LuaValue lteq(LuaValue rhs) {
    return LuaValue.valueOf(lteq_b(rhs));
  }

  @Override
  public LuaValue gteq(LuaValue rhs) {
    return LuaValue.valueOf(gteq_b(rhs));
  }

  @Override
  public LuaValue eq(LuaValue val) {
    return LuaValue.valueOf(eq_b(val));
  }

  private int compareWith(LuaValue other) {
    if (other instanceof LuaInt64 o) {
      return Long.compare(value, o.value);
    }
    if (other.isnumber()) {
      return compareToNumber(other.todouble());
    }
    throw new LuaError("int64: cannot compare " + exact + " with a " + other.typename());
  }

  /** Exact comparison against a double, which may itself be fractional or far outside the range. */
  private int compareToNumber(double other) {
    if (Double.isNaN(other)) {
      throw new LuaError("int64: cannot compare " + exact + " with NaN");
    }
    if (other == Double.POSITIVE_INFINITY) {
      return -1;
    }
    if (other == Double.NEGATIVE_INFINITY) {
      return 1;
    }
    // new BigDecimal(long) and new BigDecimal(double) are both exact, so this compares the two
    // values as mathematics rather than as doubles.
    return new BigDecimal(value).compareTo(new BigDecimal(other));
  }

  // ------------------------------------------------------------ rendering

  @Override
  public String tojstring() {
    return exact;
  }

  /**
   * String coercion resolves to the exact decimal text, so {@code string.format('%s', v)} and
   * {@code minecheck.log(v)} behave. ({@code '%d'} is not usable for a value past 2^31: this LuaJ
   * build reads {@code %d} through a 32-bit checkint, so use {@code tostring} or {@code '%x'}.)
   */
  @Override
  public LuaString checkstring() {
    return LuaString.valueOf(exact);
  }

  @Override
  public String checkjstring() {
    return exact;
  }

  @Override
  public String toString() {
    return exact;
  }

  // ------------------------------------------------------------ helpers

  /** The text of one concatenation operand: exact for an int64, Lua's own rendering otherwise. */
  private static String text(LuaValue v) {
    if (v instanceof LuaInt64 i) {
      return i.exact;
    }
    if (v.isnumber() || v.isstring()) {
      return v.tojstring();
    }
    throw new LuaError("int64: cannot concatenate a " + v.typename());
  }

  private static LuaInt64 asInt64(String how, LuaValue v) {
    if (v instanceof LuaInt64 i) {
      return i;
    }
    throw new LuaError("int64." + how + ": expected an int64, got a " + v.typename());
  }

  /** One operand of an arithmetic operation, as a double. */
  private static double numeric(String op, LuaValue v) {
    if (v instanceof LuaInt64 i) {
      return i.asDouble();
    }
    if (v.isnumber()) {
      return v.todouble();
    }
    throw new LuaError("int64: cannot use a " + v.typename() + " as a number in '" + op + "'");
  }

  private static LuaValue arithmetic(String op, DoubleBinaryOperator f) {
    return new VarArgFunction() {
      @Override
      public Varargs invoke(Varargs args) {
        return LuaValue.valueOf(
            f.applyAsDouble(numeric(op, args.arg1()), numeric(op, args.arg(2))));
      }
    };
  }
}
