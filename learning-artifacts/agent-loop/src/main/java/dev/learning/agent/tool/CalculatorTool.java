package dev.learning.agent.tool;

import dev.learning.agent.deepseek.model.ToolSpec;
import dev.learning.agent.deepseek.model.ToolSpec.FunctionSpec;
import dev.learning.agent.deepseek.model.ToolSpec.ParameterSchema;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;

public final class CalculatorTool implements AgentTool {
    private final ObjectMapper json;

    public CalculatorTool(ObjectMapper json) { this.json = json; }

    public ToolSpec definition() {
        return new ToolSpec("function", new FunctionSpec("calculator",
                "Calculate basic arithmetic using +, -, *, / and parentheses.",
                ParameterSchema.oneString("expression", "The arithmetic expression to calculate.")));
    }

    public String execute(String arguments) throws Exception {
        CalculatorArgs args = json.readValue(arguments, CalculatorArgs.class);
        return new Parser(args.expression()).parse();
    }

    record CalculatorArgs(String expression) {}

    private static final class Parser {
        private final String input;
        private int pos;
        private int operations;

        Parser(String input) {
            if (input == null || input.length() > 200) throw new IllegalArgumentException("expression is missing or too long");
            this.input = input;
        }

        String parse() {
            BigDecimal result = expression(0);
            space();
            if (pos != input.length()) throw new IllegalArgumentException("unexpected character at " + pos);
            return result.stripTrailingZeros().toPlainString();
        }

        private BigDecimal expression(int depth) {
            BigDecimal left = term(depth + 1);
            while (true) {
                space();
                if (take('+')) left = left.add(term(depth + 1));
                else if (take('-')) left = left.subtract(term(depth + 1));
                else return left;
                limit();
            }
        }

        private BigDecimal term(int depth) {
            BigDecimal left = factor(depth + 1);
            while (true) {
                space();
                if (take('*')) left = left.multiply(factor(depth + 1), MathContext.DECIMAL64);
                else if (take('/')) left = left.divide(factor(depth + 1), MathContext.DECIMAL64);
                else return left;
                limit();
            }
        }

        private BigDecimal factor(int depth) {
            if (depth > 40) throw new IllegalArgumentException("expression nesting too deep");
            space();
            if (take('+')) return factor(depth + 1);
            if (take('-')) return factor(depth + 1).negate();
            if (take('(')) {
                BigDecimal value = expression(depth + 1);
                space();
                if (!take(')')) throw new IllegalArgumentException("missing closing parenthesis");
                return value;
            }
            int start = pos;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            if (take('.')) while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            if (start == pos) throw new IllegalArgumentException("expected number at " + pos);
            try { return new BigDecimal(input.substring(start, pos)); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("invalid number"); }
        }

        private void limit() { if (++operations > 100) throw new IllegalArgumentException("too many operations"); }
        private void space() { while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++; }
        private boolean take(char c) { if (pos < input.length() && input.charAt(pos) == c) { pos++; return true; } return false; }
    }
}
