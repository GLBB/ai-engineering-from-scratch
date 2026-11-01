package dev.learning.agent.core.rewoo;

import dev.learning.agent.core.*;
import dev.learning.agent.core.planning.PlanValidationException;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EvidenceBinder {
    private static final Pattern REFERENCES = Pattern.compile("#E[0-9]*");
    private EvidenceBinder() {}

    public static String value(ToolInput input) {
        if (input instanceof CalculatorInput args) return args.expression();
        if (input instanceof SearchInput args) return args.query();
        return ((BashInput) input).command();
    }

    public static Set<String> references(ToolInput input) throws PlanValidationException {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher matcher = REFERENCES.matcher(value(input));
        while (matcher.find()) {
            String id = matcher.group().substring(1);
            if (!id.matches("E[1-9][0-9]*")) throw new PlanValidationException("invalid evidence reference " + matcher.group());
            ids.add(id);
        }
        return Set.copyOf(ids);
    }

    public static ToolInput bind(ToolInput template, EvidenceStore store) throws IOException {
        Matcher matcher = REFERENCES.matcher(value(template));
        StringBuffer text = new StringBuffer();
        while (matcher.find()) {
            String value;
            try { value = store.requireUsable(matcher.group().substring(1)).result().content(); }
            catch (IllegalArgumentException e) { throw new PlanValidationException(e.getMessage()); }
            matcher.appendReplacement(text, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(text);
        ToolInput bound;
        if (template instanceof CalculatorInput) bound = new CalculatorInput(text.toString());
        else if (template instanceof SearchInput) bound = new SearchInput(text.toString());
        else bound = new BashInput(text.toString());
        return StrictJson.toolInput(bound.toolName(), StrictJson.write(bound));
    }
}
