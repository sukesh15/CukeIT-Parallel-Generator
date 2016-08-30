package gherkin;

import gherkin.ast.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;


public class AstBuilder implements gherkin.Parser.Builder<GherkinDocument> {
    private Deque<AstNode> stack;
    private List<Comment> comments;

    public AstBuilder() {
        reset();
    }

    @Override
    public void reset() {
        stack = new ArrayDeque<>();
        stack.push(new AstNode(gherkin.Parser.RuleType.None));

        comments = new ArrayList<>();
    }

    private AstNode currentNode() {
        return stack.peek();
    }

    @Override
    public void build(Token token) {
        gherkin.Parser.RuleType ruleType = gherkin.Parser.RuleType.cast(token.matchedType);
        if (token.matchedType == gherkin.Parser.TokenType.Comment) {
            comments.add(new Comment(getLocation(token, 0), token.matchedText));
        } else {
            currentNode().add(ruleType, token);
        }
    }

    @Override
    public void startRule(gherkin.Parser.RuleType ruleType) {
        stack.push(new AstNode(ruleType));
    }

    @Override
    public void endRule(gherkin.Parser.RuleType ruleType) {
        AstNode node = stack.pop();
        Object transformedNode = getTransformedNode(node);
        currentNode().add(node.ruleType, transformedNode);
    }

    private Object getTransformedNode(AstNode node) {
        switch (node.ruleType) {
            case Step: {
                Token stepLine = node.getToken(gherkin.Parser.TokenType.StepLine);
                Node stepArg = node.getSingle(gherkin.Parser.RuleType.DataTable, null);
                if (stepArg == null) {
                    stepArg = node.getSingle(gherkin.Parser.RuleType.DocString, null);
                }
                return new Step(getLocation(stepLine, 0),
                    stepLine.matchedKeyword, stepLine.matchedText, stepArg);
            }
            case DocString: {
                Token separatorToken =
                    node.getTokens(gherkin.Parser.TokenType.DocStringSeparator).get(0);
                String contentType =
                    separatorToken.matchedText.length() > 0 ? separatorToken.matchedText : null;
                List<Token> lineTokens =
                    node.getTokens(gherkin.Parser.TokenType.Other);
                StringBuilder content = new StringBuilder();
                boolean newLine = false;
                for (Token lineToken : lineTokens) {
                    if (newLine) {
                        content.append("\n");
                    }
                    newLine = true;
                    content.append(lineToken.matchedText);
                }
                return new DocString(getLocation(separatorToken, 0),
                    contentType, content.toString());
            }
            case DataTable: {
                List<TableRow> rows = getTableRows(node);
                return new DataTable(rows);
            }
            case Background: {
                Token backgroundLine = node.getToken(gherkin.Parser.TokenType.BackgroundLine);
                String description = getDescription(node);
                List<Step> steps = getSteps(node);
                return new Background(getLocation(backgroundLine, 0),
                    backgroundLine.matchedKeyword, backgroundLine.matchedText, description, steps);
            }
            case Scenario_Definition: {
                List<Tag> tags = getTags(node);
                AstNode scenarioNode = node.getSingle(gherkin.Parser.RuleType.Scenario, null);

                if (scenarioNode != null) {
                    Token scenarioLine =
                        scenarioNode.getToken(gherkin.Parser.TokenType.ScenarioLine);
                    String description = getDescription(scenarioNode);
                    List<Step> steps = getSteps(scenarioNode);

                    return new Scenario(tags, getLocation(scenarioLine, 0),
                        scenarioLine.matchedKeyword, scenarioLine.matchedText, description, steps);
                } else {
                    AstNode scenarioOutlineNode =
                        node.getSingle(gherkin.Parser.RuleType.ScenarioOutline, null);
                    if (scenarioOutlineNode == null) {
                        throw new RuntimeException("Internal grammar error");
                    }
                    Token scenarioOutlineLine =
                        scenarioOutlineNode.getToken(gherkin.Parser.TokenType.ScenarioOutlineLine);
                    String description = getDescription(scenarioOutlineNode);
                    List<Step> steps = getSteps(scenarioOutlineNode);

                    List<Examples> examplesList =
                        scenarioOutlineNode.getItems(gherkin.Parser.RuleType.Examples_Definition);

                    return new ScenarioOutline(tags,
                        getLocation(scenarioOutlineLine, 0),
                        scenarioOutlineLine.matchedKeyword,
                        scenarioOutlineLine.matchedText,
                        description, steps, examplesList);

                }
            }
            case Examples_Definition: {
                List<Tag> tags = getTags(node);
                AstNode examplesNode = node.getSingle(gherkin.Parser.RuleType.Examples, null);
                Token examplesLine = examplesNode.getToken(gherkin.Parser.TokenType.ExamplesLine);
                String description = getDescription(examplesNode);
                List<TableRow> rows =
                    examplesNode.getSingle(gherkin.Parser.RuleType.Examples_Table, null);
                TableRow tableHeader = rows != null && !rows.isEmpty() ? rows.get(0) : null;
                List<TableRow> tableBody =
                    rows != null && !rows.isEmpty() ? rows.subList(1, rows.size()) : null;
                return new Examples(getLocation(examplesLine, 0),
                    tags, examplesLine.matchedKeyword,
                    examplesLine.matchedText,
                    description, tableHeader, tableBody);
            }
            case Examples_Table: {
                return getTableRows(node);
            }
            case Description: {
                List<Token> lineTokens = node.getTokens(gherkin.Parser.TokenType.Other);
                // Trim trailing empty lines
                int end = lineTokens.size();
                while (end > 0 && lineTokens.get(end - 1).matchedText.matches("\\s*")) {
                    end--;
                }
                lineTokens = lineTokens.subList(0, end);

                return gherkin.StringUtils.join(new StringUtils.ToString<Token>() {
                    @Override
                    public String toString(Token t) {
                        return t.matchedText;
                    }
                }, "\n", lineTokens);
            }
            case Feature: {
                AstNode header = node.getSingle(gherkin.Parser.RuleType.Feature_Header,
                    new AstNode(gherkin.Parser.RuleType.Feature_Header));
                if (header == null) {
                    return null;
                }

                Token featureLine = header.getToken(gherkin.Parser.TokenType.FeatureLine);
                if (featureLine == null) {
                    return null;
                }
                List<ScenarioDefinition> scenarioDefinitions = new ArrayList<>();
                Background background = node.getSingle(gherkin.Parser.RuleType.Background, null);
                if (background != null) {
                    scenarioDefinitions.add(background);
                }
                scenarioDefinitions.addAll(node
                    .<ScenarioDefinition>getItems(gherkin.Parser.RuleType.Scenario_Definition));
                String description = getDescription(header);
                if (featureLine.matchedGherkinDialect == null) {
                    return null;
                }
                String language = featureLine.matchedGherkinDialect.getLanguage();
                List<Tag> tags = getTags(header);
                return new Feature(tags, getLocation(featureLine, 0),
                    language,
                    featureLine.matchedKeyword,
                    featureLine.matchedText,
                    description, scenarioDefinitions);
            }
            case GherkinDocument: {
                Feature feature = node.getSingle(gherkin.Parser.RuleType.Feature, null);

                return new GherkinDocument(feature, comments);
            }

            default:

        }
        return node;
    }

    private List<TableRow> getTableRows(AstNode node) {
        List<TableRow> rows = new ArrayList<>();
        for (Token token : node.getTokens(gherkin.Parser.TokenType.TableRow)) {
            rows.add(new TableRow(getLocation(token, 0), getCells(token)));
        }
        ensureCellCount(rows);
        return rows;
    }

    private void ensureCellCount(List<TableRow> rows) {
        if (rows.isEmpty()) {
            return;
        }

        int cellCount = rows.get(0).getCells().size();
        for (TableRow row : rows) {
            if (row.getCells().size() != cellCount) {
                throw new ParserException.AstBuilderException(
                    "inconsistent cell count within the table", row.getLocation());
            }
        }
    }

    private List<TableCell> getCells(Token token) {
        List<TableCell> cells = new ArrayList<>();
        for (GherkinLineSpan cellItem : token.mathcedItems) {
            cells.add(new TableCell(getLocation(token, cellItem.column), cellItem.text));
        }
        return cells;
    }

    private List<Step> getSteps(AstNode node) {
        return node.getItems(gherkin.Parser.RuleType.Step);
    }

    private Location getLocation(Token token, int column) {
        return column == 0 ? token.location : new Location(token.location.getLine(), column);
    }

    private String getDescription(AstNode node) {
        return node.getSingle(gherkin.Parser.RuleType.Description, null);
    }

    private List<Tag> getTags(AstNode node) {
        AstNode tagsNode = node.getSingle(gherkin.Parser.RuleType.Tags,
            new AstNode(gherkin.Parser.RuleType.None));
        if (tagsNode == null) {
            return new ArrayList<>();
        }

        List<Token> tokens = tagsNode.getTokens(gherkin.Parser.TokenType.TagLine);
        List<Tag> tags = new ArrayList<>();
        for (Token token : tokens) {
            for (GherkinLineSpan tagItem : token.mathcedItems) {
                tags.add(new Tag(getLocation(token, tagItem.column), tagItem.text));
            }
        }
        return tags;
    }

    @Override
    public GherkinDocument getResult() {
        return currentNode().getSingle(gherkin.Parser.RuleType.GherkinDocument, null);
    }
}
