package dev.learning.agent.core.rewoo;

import dev.learning.agent.core.planning.PlanValidationException;
import dev.learning.agent.core.runtime.EvidenceStore;
import dev.learning.agent.support.json.StrictJson;
import dev.learning.agent.tool.ToolInput;
import dev.learning.agent.tool.ToolRegistry;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EvidenceBinder {
    private static final Pattern REFERENCES = Pattern.compile("#E[0-9]*");
    private EvidenceBinder() {}

    public static Set<String> references(ToolInput input) throws PlanValidationException {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String text : input.templateStrings()) {
            Matcher matcher = REFERENCES.matcher(text);
            while (matcher.find()) {
                String id = matcher.group().substring(1);
                if (!id.matches("E[1-9][0-9]*")) throw new PlanValidationException("invalid evidence reference " + matcher.group());
                ids.add(id);
            }
        }
        return Set.copyOf(ids);
    }

    public static ToolInput bind(ToolInput template, EvidenceStore store, ToolRegistry tools) throws IOException {
        try {
            ToolInput bound = template.mapStrings(text -> replace(text, store));
            return tools.decode(bound.toolName(), StrictJson.write(bound));
        } catch (IllegalArgumentException failure) { throw new PlanValidationException(failure.getMessage()); }
    }

    private static String replace(String text, EvidenceStore store) {
        Matcher matcher = REFERENCES.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String value = store.requireUsable(matcher.group().substring(1)).result().content();
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
