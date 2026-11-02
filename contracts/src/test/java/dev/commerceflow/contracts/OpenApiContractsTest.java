package dev.commerceflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each OpenAPI file parses cleanly and follows the API security rules: secure by default, public
 * endpoints opt out explicitly, admin endpoints declare their role, and error responses are
 * documented.
 */
class OpenApiContractsTest {

  static List<Path> openApiFiles() {
    return ContractResources.openApiFiles();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("openApiFiles")
  void parsesWithoutErrors(Path file) {
    SwaggerParseResult result = parse(file);

    assertThat(result.getMessages()).isEmpty();
    assertThat(result.getOpenAPI()).isNotNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("openApiFiles")
  void operationsFollowTheSecurityAndErrorRules(Path file) {
    OpenAPI api = parse(file).getOpenAPI();
    List<String> problems = new ArrayList<>();

    assertThat(api.getSecurity()).as("global security requirement").isNotEmpty();

    for (Map.Entry<String, PathItem> path : api.getPaths().entrySet()) {
      for (Map.Entry<PathItem.HttpMethod, Operation> entry :
          path.getValue().readOperationsMap().entrySet()) {
        String where = entry.getKey() + " " + path.getKey();
        checkOperation(where, path.getKey(), entry.getValue(), problems);
      }
    }

    assertThat(problems).isEmpty();
  }

  private static void checkOperation(
      String where, String path, Operation op, List<String> problems) {
    Map<String, ?> responses = op.getResponses();
    List<SecurityRequirement> security = op.getSecurity();
    boolean isPublic = security != null && security.isEmpty();
    boolean isAdmin = path.contains("/admin/");

    if (op.getOperationId() == null || op.getOperationId().isBlank()) {
      problems.add(where + ": missing operationId");
    }
    if (!path.startsWith("/api/v1/")
        && !path.startsWith("/internal/v1/")
        && !path.startsWith("/.well-known/")) {
      problems.add(where + ": path must be versioned under /api/v1, /internal/v1 or /.well-known");
    }
    if (op.getRequestBody() != null && !responses.containsKey("400")) {
      problems.add(where + ": accepts a body but does not document 400");
    }
    if (!isPublic && !responses.containsKey("401")) {
      problems.add(where + ": requires a token but does not document 401");
    }
    if (isAdmin) {
      Object role = op.getExtensions() == null ? null : op.getExtensions().get("x-required-role");
      if (isPublic) {
        problems.add(where + ": admin endpoints must not be public");
      }
      if (!"ADMIN".equals(role)) {
        problems.add(where + ": admin endpoints must declare x-required-role: ADMIN");
      }
      if (!responses.containsKey("403")) {
        problems.add(where + ": admin endpoints must document 403");
      }
    }
  }

  private static SwaggerParseResult parse(Path file) {
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    return new OpenAPIV3Parser().readContents(ContractResources.read(file), null, options);
  }
}
