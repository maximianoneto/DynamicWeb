package com.tcc.dynamicweb.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tcc.dynamicweb.model.Assistant;
import com.tcc.dynamicweb.model.Project;
import com.tcc.dynamicweb.repository.AssistantRepository;
import com.tcc.dynamicweb.repository.ProjectRepository;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.tcc.dynamicweb.model.Assistant.AssistantType.CODE_GENERATOR;

@Service
public class ProjectService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String openAiBaseUrl = "https://api.openai.com/v1";
    private final Dotenv dotenv = Dotenv.configure().load();

    private static final Logger logger = LoggerFactory.getLogger(CodeService.class);
    private final ConcurrentHashMap<String, String> projectPaths = new ConcurrentHashMap<>();

    @Autowired
    private AssistantRepository assistantRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ThreadService threadService;

    @Autowired
    private CodeService codeService;

    public ResponseEntity<String> createProject(String projectName, String type, String threadId, String additionalInformation, String programmingLanguage) {
        ResponseEntity<String> response = null;
        try {

            HttpHeaders headers = threadService.createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            response = restTemplate.exchange(
                    openAiBaseUrl + "/threads/" + threadId + "/messages",
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (response.getBody() != null) {
                JsonObject responseBody = JsonParser.parseString(response.getBody()).getAsJsonObject();
                JsonArray messages = responseBody.getAsJsonArray("data");
                if (!messages.isEmpty()) {
                    JsonObject firstMessage = messages.get(0).getAsJsonObject();
                    JsonArray content = firstMessage.getAsJsonArray("content");
                    if (!content.isEmpty()) {
                        JsonObject firstContent = content.get(0).getAsJsonObject();
                        String value = firstContent.getAsJsonObject("text").get("value").getAsString();
                        if (value.isEmpty()) {
                            response = threadService.getThreadMessages(threadId);
                            System.out.println("Aguardando API");
                        } else {
                            // Verificação de padrões no primeiro item de content
                            if (codeService.containsPattern(value, "```cmd")) {
                                System.out.println("Executando comando");
                                codeService.regexCmdCode(response);
                                Project project = new Project();
                                project.setName(projectName);
                                project.setType(type);
                                project.setAdditionalInformation(additionalInformation);
                                project.setProgrammingLanguague(programmingLanguage);
                                Optional<Assistant> optionalAssistant = assistantRepository.findAssistantByThreadId(threadId);

                                if (optionalAssistant.isEmpty()){
                                    optionalAssistant.get().setType(CODE_GENERATOR);
                                }
                                optionalAssistant.get().setThreadId(threadId);

                                optionalAssistant.get().setProject(project);
                                project.getAssistants().add(optionalAssistant.get());
                                project.setPathToProject("C:\\Projects\\" + projectName);

                                projectRepository.save(project);
                            }
                        }
                    } else {
                        System.out.println("Aguardando API");
                        response = threadService.getThreadMessages(threadId);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return response;
    }

    //String currentDirectory = "/projects"; Prod
    static String currentDirectory = "C:\\Projects"; // Local

    public Path getProjectPath(String projectName) {
        String osName = System.getProperty("os.name").toLowerCase();
        Path baseProjectPath;

        if (osName.contains("win")) {
            baseProjectPath = Paths.get("C:\\Projects");
        } else {
            baseProjectPath = Paths.get("/projects");
        }

        return baseProjectPath.resolve(projectName);
    }


    public void addDependencyToProject(String projectName, String dependency) throws IOException {
        Path projectPath = getProjectPath(projectName);
        Path buildFilePath = projectPath.resolve("build.gradle");

        if (!Files.exists(buildFilePath)) {
            throw new FileNotFoundException("build.gradle file not found at: " + buildFilePath.toString());
        }

        // Ler o conteúdo existente
        List<String> lines = Files.readAllLines(buildFilePath, StandardCharsets.UTF_8);

        // Encontrar o bloco 'dependencies {'
        int dependenciesStartIndex = -1;
        int dependenciesEndIndex = -1;
        int braceCount = 0;
        boolean inDependenciesBlock = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.equals("dependencies {")) {
                dependenciesStartIndex = i;
                inDependenciesBlock = true;
                braceCount++;
            } else if (line.contains("{")) {
                braceCount++;
            } else if (line.contains("}")) {
                braceCount--;
                if (inDependenciesBlock && braceCount == 0) {
                    dependenciesEndIndex = i;
                    break;
                }
            }
        }

        if (dependenciesStartIndex != -1 && dependenciesEndIndex != -1) {
            // Inserir a nova dependência antes da chave de fechamento
            lines.add(dependenciesEndIndex, "    " + dependency);
        } else {
            // Se o bloco de dependências não for encontrado, adicioná-lo ao final
            lines.add("\ndependencies {");
            lines.add("    " + dependency);
            lines.add("}");
        }

        // Gravar o conteúdo modificado de volta no arquivo
        Files.write(buildFilePath, lines, StandardCharsets.UTF_8);
    }

    void addApplicationPropertiesToProject(String applicationBlock, String projectName) throws IOException {
        Path projectFilePath = Paths.get(String.valueOf(getProjectPath(projectName)), "src", "main", "resources", "application.properties");

        String fileContent = new String(Files.readAllBytes(projectFilePath));

        String updatedContent = fileContent + "\n" + applicationBlock;

        Files.writeString(projectFilePath, updatedContent, StandardOpenOption.TRUNCATE_EXISTING);

    }

    public void createJavaFilesInProject(Map<String, String> classesContent, String projectName) throws IOException {
        // Caminho base onde os arquivos Java devem ser salvos dentro do projeto
        String mainBasePath = getProjectPath(projectName) + File.separator + "src" + File.separator + "main" + File.separator + "java";
        String testBasePath = getProjectPath(projectName) + File.separator + "src" + File.separator + "test" + File.separator + "java";

        for (Map.Entry<String, String> classEntry : classesContent.entrySet()) {
            String className = classEntry.getKey();
            String classContent = classEntry.getValue();
            String packageName = extractPackageName(classContent); // Extrai o nome do pacote


            String basePath = className.contains("Test") ? testBasePath : mainBasePath;

            // Constrói o caminho do diretório baseado no nome do pacote
            String packagePath = packageName.replace('.', File.separatorChar);
            Path fullDirPath = Paths.get(basePath, packagePath);

            if (!Files.exists(fullDirPath)) {
                Files.createDirectories(fullDirPath);
            }

            // Cria o arquivo Java no diretório do pacote
            Path filePath = fullDirPath.resolve(className + ".java");
            Files.writeString(filePath, classContent, StandardOpenOption.CREATE);
        }
    }

    private String extractPackageName(String classContent) {
        Pattern packagePattern = Pattern.compile("package\\s+([\\w\\.]+);");
        Matcher matcher = packagePattern.matcher(classContent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public Map<String, String> extractJavaClasses(String classContent) {
        Map<String, String> classesContent = new HashMap<>();
        String className = extractClassName(classContent);

        if (className != null && !className.isEmpty()) {
            classesContent.put(className, classContent);
        }

        return classesContent;
    }

    private String extractClassName(String classContent) {
        Pattern classNamePattern = Pattern.compile("(class|interface|enum)\\s+([\\w$]+)\\s");
        Matcher classNameMatcher = classNamePattern.matcher(classContent);
        if (classNameMatcher.find()) {
            return classNameMatcher.group(2);
        }
        return null;
    }

    void createNodeFilesInProject(String path, String content, String projectName) throws IOException {
        Path pathToFile = Paths.get(String.valueOf(getProjectPath(projectName)), path); // Cria o Path para o arquivo
        if (Files.notExists(pathToFile.getParent())) {
            Files.createDirectories(pathToFile.getParent()); // Cria os diretórios pais se não existirem
        }

        // Escreve o conteúdo no arquivo, usando a opção CREATE para criar se não existir, caso contrário TRUNCATE_EXISTING para sobrescrever
        Files.writeString(pathToFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    void createPythonFilesInProject(String content, String project) throws IOException {
        Path pathToFile = Paths.get(String.valueOf(getProjectPath(project))); // Cria o Path para o arquivo
        if (Files.notExists(pathToFile.getParent())) {
            Files.createDirectories(pathToFile.getParent()); // Cria os diretórios pais se não existirem
        }
        // Escreve o conteúdo no arquivo, usando a opção CREATE para criar se não existir, caso contrário TRUNCATE_EXISTING para sobrescrever
        Files.writeString(pathToFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    void createNextFilesInProject(String path, String content, String project) throws IOException {
        Path pathToFile = Paths.get(String.valueOf(getProjectPath(project)), path); // Cria o Path para o arquivo
        if (Files.notExists(pathToFile.getParent())) {
            Files.createDirectories(pathToFile.getParent()); // Cria os diretórios pais se não existirem
        }
        // Escreve o conteúdo no arquivo, usando a opção CREATE para criar se não existir, caso contrário TRUNCATE_EXISTING para sobrescrever
        Files.writeString(pathToFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    void createReactFilesInProject(String path, String content, String projectName) throws IOException {
        Path pathToFile = Paths.get(String.valueOf(getProjectPath(projectName)), path); // Cria o Path para o arquivo
        if (Files.notExists(pathToFile.getParent())) {
            Files.createDirectories(pathToFile.getParent()); // Cria os diretórios pais se não existirem
        }
        // Escreve o conteúdo no arquivo, usando a opção CREATE para criar se não existir, caso contrário TRUNCATE_EXISTING para sobrescrever
        Files.writeString(pathToFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void zipFolder(Path sourceFolderPath, Path zipPath) throws IOException {
        logger.info("Iniciando zipping do diretório: {}", sourceFolderPath);

        if (!Files.exists(sourceFolderPath)) {
            logger.error("Source directory does not exist: {}", sourceFolderPath);
            throw new FileNotFoundException("Source directory does not exist: " + sourceFolderPath);
        }

        // Ensure the parent directories for the ZIP file exist
        Path zipParentDir = zipPath.getParent();
        if (zipParentDir != null && !Files.exists(zipParentDir)) {
            Files.createDirectories(zipParentDir);
            logger.info("Criado diretório pai para o ZIP: {}", zipParentDir);
        }

        Path nodeModulesPath = sourceFolderPath.resolve("node_modules");
        if (Files.exists(nodeModulesPath)) {
            logger.info("Deletando node_modules em: {}", nodeModulesPath);
            deleteFolder(nodeModulesPath);
        }

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            Files.walkFileTree(sourceFolderPath, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.equals(zipPath)) {
                        return FileVisitResult.CONTINUE; // Skip the ZIP file if it's inside the source directory
                    }
                    logger.info("Adicionando arquivo ao ZIP: {}", file);
                    ZipEntry zipEntry = new ZipEntry(sourceFolderPath.relativize(file).toString().replace("\\", "/"));
                    zos.putNextEntry(zipEntry);
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.equals(nodeModulesPath)) {
                        return FileVisitResult.SKIP_SUBTREE; // Skip node_modules directory
                    }
                    if (!dir.equals(sourceFolderPath)) {
                        logger.info("Adicionando diretório ao ZIP (excluindo node_modules): {}", dir);
                        ZipEntry zipEntry = new ZipEntry(sourceFolderPath.relativize(dir).toString().replace("\\", "/") + "/");
                        zos.putNextEntry(zipEntry);
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.error("Erro ao criar ZIP: {}", e.getMessage(), e);
            throw e;
        }

        logger.info("Zipping concluído com sucesso para: {}", zipPath);
    }


    private void deleteFolder(Path folderPath) throws IOException {
        logger.info("Iniciando deleção do diretório: {}", folderPath);
        Files.walk(folderPath)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    logger.info("Deletando: {}", file.getPath());
                    file.delete();
                });
        logger.info("Diretório deletado com sucesso: {}", folderPath);
    }
}
