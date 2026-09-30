package com.digitalasset.quickstart.service;

import com.daml.daml_lf_dev.DamlLf1;
import com.daml.daml_lf_dev.DamlLf2;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.config.TemplateSchemaDebugConfig;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DEVNET-only helper to inspect template parameter schemas from DALF packages.
 * Guarded by feature flag to avoid leaking in production.
 */
@Service
public class TemplateSchemaService {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateSchemaService.class);

    private final LedgerApi ledgerApi;
    private final TemplateSchemaDebugConfig debugConfig;

    // Cache packageId -> decoded package
    private final Map<String, PackageWrapper> packageCache = new ConcurrentHashMap<>();

    public TemplateSchemaService(LedgerApi ledgerApi, TemplateSchemaDebugConfig debugConfig) {
        this.ledgerApi = ledgerApi;
        this.debugConfig = debugConfig;
    }

    public boolean isEnabled() {
        return debugConfig.isEnabled();
    }

    public boolean isAuthorized(String tokenHeader) {
        String expected = debugConfig.getDebugToken();
        if (expected == null || expected.isBlank()) return true; // no token required
        return expected.equals(tokenHeader);
    }

    @WithSpan
    public SchemaResponse getTemplateSchema(String packageId, String moduleName, String entityName) throws Exception {
        if (!isEnabled()) {
            throw new IllegalStateException("Template schema debug is disabled");
        }

        PackageWrapper pkg = packageCache.computeIfAbsent(packageId, this::loadPackage);
        if (pkg == null) {
            throw new IllegalStateException("Package not found or failed to load");
        }
        if (pkg.lf2 != null) {
            return getTemplateSchemaLf2(pkg.lf2, packageId, moduleName, entityName);
        }
        return getTemplateSchemaLf1(pkg.lf1, packageId, moduleName, entityName);
    }

    @WithSpan
    public ChoicesResponse getTemplateChoices(String packageId, String moduleName, String entityName) throws Exception {
        if (!isEnabled()) {
            throw new IllegalStateException("Template schema debug is disabled");
        }

        TemplateChoicesAst ast = getTemplateChoicesAst(packageId, moduleName, entityName);
        List<ChoiceInfo> choices = ast.choices().stream()
                .map(choice -> {
                    String argType;
                    List<FieldInfo> argFields;
                    if (ast.isLf2()) {
                        argType = choice.lf2ArgType() != null ? summarizeType(ast.lf2Package(), resolveType(ast.lf2Package(), choice.lf2ArgType())) : "Unit";
                        argFields = choiceArgFieldsLf2(ast.lf2Package(), choice.lf2ArgType());
                    } else {
                        argType = choice.lf1ArgType() != null ? summarizeType(ast.lf1Package(), resolveType(ast.lf1Package(), choice.lf1ArgType())) : "Unit";
                        argFields = choiceArgFieldsLf1(ast.lf1Package(), choice.lf1ArgType());
                    }
                    return new ChoiceInfo(choice.name(), choice.consuming(), argType, argFields);
                })
                .toList();
        return new ChoicesResponse(packageId, moduleName, entityName, choices);
    }

    public TemplateChoicesAst getTemplateChoicesAst(String packageId, String moduleName, String entityName) {
        PackageWrapper pkg = packageCache.computeIfAbsent(packageId, this::loadPackage);
        if (pkg == null) {
            throw new IllegalStateException("Package not found or failed to load");
        }
        if (pkg.lf2 != null) {
            return getTemplateChoicesAstLf2(pkg.lf2, moduleName, entityName);
        }
        return getTemplateChoicesAstLf1(pkg.lf1, moduleName, entityName);
    }

    public DataTypeAst getDataTypeAst(String packageId, String moduleName, String entityName) {
        PackageWrapper pkg = packageCache.computeIfAbsent(packageId, this::loadPackage);
        if (pkg == null) {
            throw new IllegalStateException("Package not found or failed to load");
        }
        if (pkg.lf2 != null) {
            return getDataTypeAstLf2(pkg.lf2, moduleName, entityName);
        }
        return getDataTypeAstLf1(pkg.lf1, moduleName, entityName);
    }

    private SchemaResponse getTemplateSchemaLf1(DamlLf1.Package pkg, String packageId, String moduleName, String entityName) {
        var module = pkg.getModulesList().stream()
                .filter(m -> moduleName.equals(resolveModuleName(pkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleName));

        // Locate the data type that backs the template's create arguments
        var dataType = module.getDataTypesList().stream()
                .filter(dt -> entityName.equals(resolveDottedName(pkg, dt)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data type not found: " + entityName));

        // Param name from the template definition (if present)
        String paramName = module.getTemplatesList().stream()
                .filter(t -> entityName.equals(resolveTemplateName(pkg, t)))
                .map(t -> t.getParamCase() == DamlLf1.DefTemplate.ParamCase.PARAM_INTERNED_STR
                        ? pkg.getInternedStrings(t.getParamInternedStr())
                        : t.getParamStr())
                .findFirst()
                .orElse("-");

        List<FieldInfo> fields = new ArrayList<>();
        if (dataType.hasRecord()) {
            int idx = 0;
            for (var f : dataType.getRecord().getFieldsList()) {
                String name = resolveFieldName(pkg, f);
                String type = summarizeType(pkg, f.getType());
                fields.add(new FieldInfo(idx++, name, type));
            }
        }

        return new SchemaResponse(packageId, moduleName, entityName, paramName, fields);
    }

    private SchemaResponse getTemplateSchemaLf2(DamlLf2.Package pkg, String packageId, String moduleName, String entityName) {
        var module = pkg.getModulesList().stream()
                .filter(m -> moduleName.equals(resolveModuleName(pkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleName));

        var template = module.getTemplatesList().stream()
                .filter(t -> entityName.equals(resolveTemplateName(pkg, t)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + entityName));

        String dataName = dottedNameFromInterned(pkg, template.getTyconInternedDname());
        var dataType = module.getDataTypesList().stream()
                .filter(dt -> dataName.equals(resolveDottedName(pkg, dt)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data type not found: " + dataName));

        String paramName = pkg.getInternedStrings(template.getParamInternedStr());
        List<FieldInfo> fields = new ArrayList<>();
        if (dataType.hasRecord()) {
            int idx = 0;
            for (var f : dataType.getRecord().getFieldsList()) {
                String name = resolveFieldName(pkg, f);
                String type = summarizeType(pkg, f.getType());
                fields.add(new FieldInfo(idx++, name, type));
            }
        }

        return new SchemaResponse(packageId, moduleName, entityName, paramName, fields);
    }

    private TemplateChoicesAst getTemplateChoicesAstLf1(DamlLf1.Package pkg, String moduleName, String entityName) {
        var module = pkg.getModulesList().stream()
                .filter(m -> moduleName.equals(resolveModuleName(pkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleName));

        var template = module.getTemplatesList().stream()
                .filter(t -> entityName.equals(resolveTemplateName(pkg, t)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + entityName));

        List<ChoiceAst> choices = new ArrayList<>();
        for (var c : template.getChoicesList()) {
            String name = switch (c.getNameCase()) {
                case NAME_STR -> c.getNameStr();
                case NAME_INTERNED_STR -> pkg.getInternedStrings(c.getNameInternedStr());
                case NAME_NOT_SET -> "<choice>";
            };
            DamlLf1.Type argType = c.hasArgBinder() ? c.getArgBinder().getType() : null;
            choices.add(new ChoiceAst(name, c.getConsuming(), argType, null));
        }
        return new TemplateChoicesAst(pkg, null, choices);
    }

    private TemplateChoicesAst getTemplateChoicesAstLf2(DamlLf2.Package pkg, String moduleName, String entityName) {
        var module = pkg.getModulesList().stream()
                .filter(m -> moduleName.equals(resolveModuleName(pkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleName));

        var template = module.getTemplatesList().stream()
                .filter(t -> entityName.equals(resolveTemplateName(pkg, t)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + entityName));

        List<ChoiceAst> choices = new ArrayList<>();
        for (var c : template.getChoicesList()) {
            String name = pkg.getInternedStrings(c.getNameInternedStr());
            DamlLf2.Type argType = c.hasArgBinder() ? c.getArgBinder().getType() : null;
            choices.add(new ChoiceAst(name, c.getConsuming(), null, argType));
        }
        return new TemplateChoicesAst(null, pkg, choices);
    }

    private DataTypeAst getDataTypeAstLf1(DamlLf1.Package pkg, String moduleName, String entityName) {
        var module = pkg.getModulesList().stream()
                .filter(m -> moduleName.equals(resolveModuleName(pkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleName));
        var dataType = module.getDataTypesList().stream()
                .filter(dt -> entityName.equals(resolveDottedName(pkg, dt)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data type not found: " + entityName));
        if (!dataType.hasRecord()) {
            throw new IllegalStateException("Data type is not a record");
        }
        return new DataTypeAst(pkg, null, dataType.getRecord().getFieldsList(), null);
    }

    private DataTypeAst getDataTypeAstLf2(DamlLf2.Package pkg, String moduleName, String entityName) {
        var module = pkg.getModulesList().stream()
                .filter(m -> moduleName.equals(resolveModuleName(pkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleName));
        var dataType = module.getDataTypesList().stream()
                .filter(dt -> entityName.equals(resolveDottedName(pkg, dt)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data type not found: " + entityName));
        if (!dataType.hasRecord()) {
            throw new IllegalStateException("Data type is not a record");
        }
        return new DataTypeAst(null, pkg, null, dataType.getRecord().getFieldsList());
    }

    private PackageWrapper loadPackage(String packageId) {
        try {
            byte[] rawBytes = ledgerApi.getPackageBytes(packageId);

            byte[] dalfBytes = rawBytes;
            byte[] extracted = extractDalfFromZip(rawBytes);
            if (extracted != null) {
                dalfBytes = extracted;
            }

            byte[] payloadCandidate = extractLargestLengthDelimited(dalfBytes);
            if (payloadCandidate == null) {
                LOG.error("Failed to extract payload candidate for {}", packageId);
                return null;
            }

            DamlLf1.Package lf1 = tryParseLf1(payloadCandidate);
            if (lf1 != null) {
                return new PackageWrapper(lf1, null);
            }
            DamlLf2.Package lf2 = tryParseLf2(payloadCandidate);
            if (lf2 != null) {
                return new PackageWrapper(null, lf2);
            }

            LOG.error("Unable to decode package payload for {}", packageId);
            return null;
        } catch (Exception e) {
            LOG.error("Failed to load package {}: {}", packageId, e.getMessage(), e);
            return null;
        }
    }

    private DamlLf1.Package tryParseLf1(byte[] bytes) {
        try {
            return DamlLf1.Package.parseFrom(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private DamlLf2.Package tryParseLf2(byte[] bytes) {
        try {
            return DamlLf2.Package.parseFrom(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] extractDalfFromZip(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        // ZIP file magic: 0x50 0x4B 0x03 0x04
        if (bytes[0] != 0x50 || bytes[1] != 0x4B) {
            return null;
        }
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name != null && name.endsWith(".dalf")) {
                    return zis.readAllBytes();
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to extract .dalf from zip: {}", e.getMessage());
        }
        return null;
    }

    private byte[] extractLargestLengthDelimited(byte[] bytes) {
        try {
            CodedInputStream in = CodedInputStream.newInstance(bytes);
            byte[] largest = null;
            while (!in.isAtEnd()) {
                int tag = in.readTag();
                if (tag == 0) {
                    break;
                }
                int wireType = WireFormat.getTagWireType(tag);
                if (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    byte[] val = in.readBytes().toByteArray();
                    if (largest == null || val.length > largest.length) {
                        largest = val;
                    }
                } else {
                    in.skipField(tag);
                }
            }
            return largest;
        } catch (Exception e) {
            LOG.error("Failed to extract payload candidate: {}", e.getMessage());
            return null;
        }
    }

    private String resolveModuleName(DamlLf1.Package pkg, DamlLf1.Module module) {
        return switch (module.getNameCase()) {
            case NAME_DNAME -> String.join(".", module.getNameDname().getSegmentsList());
            case NAME_INTERNED_DNAME -> dottedNameFromInterned(pkg, module.getNameInternedDname());
            default -> "<unknown>";
        };
    }

    private String resolveModuleName(DamlLf2.Package pkg, DamlLf2.Module module) {
        return dottedNameFromInterned(pkg, module.getNameInternedDname());
    }

    private String resolveDottedName(DamlLf1.Package pkg, DamlLf1.DefDataType dt) {
        return switch (dt.getNameCase()) {
            case NAME_DNAME -> String.join(".", dt.getNameDname().getSegmentsList());
            case NAME_INTERNED_DNAME -> dottedNameFromInterned(pkg, dt.getNameInternedDname());
            default -> "<unknown>";
        };
    }

    private String resolveDottedName(DamlLf2.Package pkg, DamlLf2.DefDataType dt) {
        return dottedNameFromInterned(pkg, dt.getNameInternedDname());
    }

    private String resolveTemplateName(DamlLf1.Package pkg, DamlLf1.DefTemplate tmpl) {
        return switch (tmpl.getTyconCase()) {
            case TYCON_DNAME -> String.join(".", tmpl.getTyconDname().getSegmentsList());
            case TYCON_INTERNED_DNAME -> dottedNameFromInterned(pkg, tmpl.getTyconInternedDname());
            default -> "<unknown>";
        };
    }

    private String resolveTemplateName(DamlLf2.Package pkg, DamlLf2.DefTemplate tmpl) {
        return dottedNameFromInterned(pkg, tmpl.getTyconInternedDname());
    }

    private String resolveFieldName(DamlLf1.Package pkg, DamlLf1.FieldWithType f) {
        return switch (f.getFieldCase()) {
            case FIELD_STR -> f.getFieldStr();
            case FIELD_INTERNED_STR -> pkg.getInternedStrings(f.getFieldInternedStr());
            default -> "<unknown>";
        };
    }

    private String resolveFieldName(DamlLf2.Package pkg, DamlLf2.FieldWithType f) {
        return pkg.getInternedStrings(f.getFieldInternedStr());
    }

    private String dottedNameFromInterned(DamlLf1.Package pkg, int internedIndex) {
        if (internedIndex < 0 || internedIndex >= pkg.getInternedDottedNamesCount()) return "<bad-index>";
        var interned = pkg.getInternedDottedNames(internedIndex);
        List<String> segments = new ArrayList<>();
        for (int idx : interned.getSegmentsInternedStrList()) {
            segments.add(pkg.getInternedStrings(idx));
        }
        return String.join(".", segments);
    }

    private String dottedNameFromInterned(DamlLf2.Package pkg, int internedIndex) {
        if (internedIndex < 0 || internedIndex >= pkg.getInternedDottedNamesCount()) return "<bad-index>";
        var interned = pkg.getInternedDottedNames(internedIndex);
        List<String> segments = new ArrayList<>();
        for (int idx : interned.getSegmentsInternedStrList()) {
            segments.add(pkg.getInternedStrings(idx));
        }
        return String.join(".", segments);
    }

    private String summarizeType(DamlLf1.Package pkg, DamlLf1.Type ty) {
        return switch (ty.getSumCase()) {
            case PRIM -> summarizePrim(pkg, ty.getPrim());
            case VAR -> {
                var v = ty.getVar();
                String name = switch (v.getVarCase()) {
                    case VAR_STR -> v.getVarStr();
                    case VAR_INTERNED_STR -> pkg.getInternedStrings(v.getVarInternedStr());
                    default -> "<var>";
                };
                if (v.getArgsCount() > 0) {
                    List<String> args = new ArrayList<>();
                    for (var a : v.getArgsList()) args.add(summarizeType(pkg, resolveType(pkg, a)));
                    yield name + "<" + String.join(",", args) + ">";
                }
                yield name;
            }
            case CON -> {
                var con = ty.getCon();
                String tycon = summarizeTypeCon(pkg, con.getTycon());
                if (con.getArgsCount() > 0) {
                    List<String> args = new ArrayList<>();
                    for (var a : con.getArgsList()) args.add(summarizeType(pkg, resolveType(pkg, a)));
                    yield tycon + "<" + String.join(",", args) + ">";
                }
                yield tycon;
            }
            case STRUCT -> "Struct";
            case NAT -> "Nat";
            case FORALL -> "Forall";
            case SYN -> "Syn";
            case INTERNED -> summarizeType(pkg, pkg.getInternedTypes(ty.getInterned()));
            case SUM_NOT_SET -> "<unknown>";
            default -> "<unknown>";
        };
    }

    private String summarizeType(DamlLf2.Package pkg, DamlLf2.Type ty) {
        return switch (ty.getSumCase()) {
            case BUILTIN -> summarizeBuiltin(pkg, ty.getBuiltin());
            case VAR -> {
                var v = ty.getVar();
                String name = pkg.getInternedStrings(v.getVarInternedStr());
                if (v.getArgsCount() > 0) {
                    List<String> args = new ArrayList<>();
                    for (var a : v.getArgsList()) args.add(summarizeType(pkg, resolveType(pkg, a)));
                    yield name + "<" + String.join(",", args) + ">";
                }
                yield name;
            }
            case CON -> {
                var con = ty.getCon();
                String tycon = summarizeTypeCon(pkg, con.getTycon());
                if (con.getArgsCount() > 0) {
                    List<String> args = new ArrayList<>();
                    for (var a : con.getArgsList()) args.add(summarizeType(pkg, resolveType(pkg, a)));
                    yield tycon + "<" + String.join(",", args) + ">";
                }
                yield tycon;
            }
            case STRUCT -> "Struct";
            case NAT -> "Nat";
            case FORALL -> "Forall";
            case SYN -> "Syn";
            case INTERNED -> summarizeType(pkg, pkg.getInternedTypes(ty.getInterned()));
            case SUM_NOT_SET -> "<unknown>";
            default -> "<unknown>";
        };
    }

    private String summarizePrim(DamlLf1.Package pkg, DamlLf1.Type.Prim prim) {
        return switch (prim.getPrim()) {
            case LIST -> {
                String arg = prim.getArgsCount() > 0 ? summarizeType(pkg, resolveType(pkg, prim.getArgs(0))) : "?";
                yield "List<" + arg + ">";
            }
            case OPTIONAL -> {
                String arg = prim.getArgsCount() > 0 ? summarizeType(pkg, resolveType(pkg, prim.getArgs(0))) : "?";
                yield "Optional<" + arg + ">";
            }
            case TEXTMAP -> {
                String arg = prim.getArgsCount() > 0 ? summarizeType(pkg, resolveType(pkg, prim.getArgs(0))) : "?";
                yield "TextMap<" + arg + ">";
            }
            case GENMAP -> {
                String k = prim.getArgsCount() > 0 ? summarizeType(pkg, resolveType(pkg, prim.getArgs(0))) : "?";
                String v = prim.getArgsCount() > 1 ? summarizeType(pkg, resolveType(pkg, prim.getArgs(1))) : "?";
                yield "GenMap<" + k + "," + v + ">";
            }
            default -> prim.getPrim().name();
        };
    }

    private String summarizeBuiltin(DamlLf2.Package pkg, DamlLf2.Type.Builtin builtin) {
        return switch (builtin.getBuiltin()) {
            case LIST -> {
                String arg = builtin.getArgsCount() > 0 ? summarizeType(pkg, resolveType(pkg, builtin.getArgs(0))) : "?";
                yield "List<" + arg + ">";
            }
            case OPTIONAL -> {
                String arg = builtin.getArgsCount() > 0 ? summarizeType(pkg, resolveType(pkg, builtin.getArgs(0))) : "?";
                yield "Optional<" + arg + ">";
            }
            case TEXTMAP -> {
                String arg = builtin.getArgsCount() > 0 ? summarizeType(pkg, resolveType(pkg, builtin.getArgs(0))) : "?";
                yield "TextMap<" + arg + ">";
            }
            case GENMAP -> {
                String k = builtin.getArgsCount() > 0 ? summarizeType(pkg, resolveType(pkg, builtin.getArgs(0))) : "?";
                String v = builtin.getArgsCount() > 1 ? summarizeType(pkg, resolveType(pkg, builtin.getArgs(1))) : "?";
                yield "GenMap<" + k + "," + v + ">";
            }
            default -> builtin.getBuiltin().name();
        };
    }

    private String summarizeTypeCon(DamlLf1.Package pkg, DamlLf1.TypeConName name) {
        String mod = name.hasModule()
                ? resolveModuleRef(pkg, name.getModule())
                : "<mod>";
        String ent = switch (name.getNameCase()) {
            case NAME_DNAME -> String.join(".", name.getNameDname().getSegmentsList());
            case NAME_INTERNED_DNAME -> dottedNameFromInterned(pkg, name.getNameInternedDname());
            case NAME_NOT_SET -> "<name>";
        };
        return mod + ":" + ent;
    }

    private String summarizeTypeCon(DamlLf2.Package pkg, DamlLf2.TypeConName name) {
        String mod = name.hasModule()
                ? resolveModuleRef(pkg, name.getModule())
                : "<mod>";
        String ent = dottedNameFromInterned(pkg, name.getNameInternedDname());
        return mod + ":" + ent;
    }

    private String resolveModuleRef(DamlLf1.Package pkg, DamlLf1.ModuleRef moduleRef) {
        switch (moduleRef.getModuleNameCase()) {
            case MODULE_NAME_DNAME:
                return String.join(".", moduleRef.getModuleNameDname().getSegmentsList());
            case MODULE_NAME_INTERNED_DNAME:
                return dottedNameFromInterned(pkg, moduleRef.getModuleNameInternedDname());
            default:
                return "<module>";
        }
    }

    private String resolveModuleRef(DamlLf2.Package pkg, DamlLf2.ModuleRef moduleRef) {
        return dottedNameFromInterned(pkg, moduleRef.getModuleNameInternedDname());
    }

    private List<FieldInfo> choiceArgFieldsLf1(DamlLf1.Package pkg, DamlLf1.Type argType) {
        if (argType == null) {
            return List.of();
        }
        argType = resolveType(pkg, argType);
        if (argType.getSumCase() != DamlLf1.Type.SumCase.CON) {
            return List.of();
        }
        DamlLf1.Type.Con con = argType.getCon();
        String modName = resolveModuleRef(pkg, con.getTycon().getModule());
        if (modName.startsWith("DA.Internal")) {
            return List.of();
        }
        ResolvedDataTypeLf1 resolved = resolveDataTypeLf1(pkg, con.getTycon());
        if (!resolved.def().hasRecord()) {
            return List.of();
        }
        List<FieldInfo> fields = new ArrayList<>();
        int idx = 0;
        for (var f : resolved.def().getRecord().getFieldsList()) {
            String name = resolveFieldName(resolved.pkg(), f);
            String type = summarizeType(resolved.pkg(), resolveType(resolved.pkg(), f.getType()));
            fields.add(new FieldInfo(idx++, name, type));
        }
        return fields;
    }

    private List<FieldInfo> choiceArgFieldsLf2(DamlLf2.Package pkg, DamlLf2.Type argType) {
        if (argType == null) {
            return List.of();
        }
        argType = resolveType(pkg, argType);
        if (argType.getSumCase() != DamlLf2.Type.SumCase.CON) {
            return List.of();
        }
        DamlLf2.Type.Con con = argType.getCon();
        String modName = resolveModuleRef(pkg, con.getTycon().getModule());
        if (modName.startsWith("DA.Internal")) {
            return List.of();
        }
        ResolvedDataTypeLf2 resolved = resolveDataTypeLf2(pkg, con.getTycon());
        if (!resolved.def().hasRecord()) {
            return List.of();
        }
        List<FieldInfo> fields = new ArrayList<>();
        int idx = 0;
        for (var f : resolved.def().getRecord().getFieldsList()) {
            String name = resolveFieldName(resolved.pkg(), f);
            String type = summarizeType(resolved.pkg(), resolveType(resolved.pkg(), f.getType()));
            fields.add(new FieldInfo(idx++, name, type));
        }
        return fields;
    }

    private PackageWrapper resolvePackageRef(DamlLf1.Package currentPkg, DamlLf1.ModuleRef moduleRef) {
        if (!moduleRef.hasPackageRef()) {
            return new PackageWrapper(currentPkg, null);
        }
        DamlLf1.PackageRef ref = moduleRef.getPackageRef();
        String pkgId = switch (ref.getSumCase()) {
            case SELF -> null;
            case PACKAGE_ID_STR -> ref.getPackageIdStr();
            case PACKAGE_ID_INTERNED_STR -> currentPkg.getInternedStrings(ref.getPackageIdInternedStr());
            case SUM_NOT_SET -> null;
        };
        if (pkgId == null || pkgId.isBlank()) {
            return new PackageWrapper(currentPkg, null);
        }
        return packageCache.computeIfAbsent(pkgId, this::loadPackage);
    }

    private PackageWrapper resolvePackageRef(DamlLf2.Package currentPkg, DamlLf2.ModuleRef moduleRef) {
        if (!moduleRef.hasPackageRef()) {
            return new PackageWrapper(null, currentPkg);
        }
        DamlLf2.PackageRef ref = moduleRef.getPackageRef();
        String pkgId = switch (ref.getSumCase()) {
            case SELF -> null;
            case PACKAGE_ID_INTERNED_STR -> currentPkg.getInternedStrings(ref.getPackageIdInternedStr());
            case SUM_NOT_SET -> null;
        };
        if (pkgId == null || pkgId.isBlank()) {
            return new PackageWrapper(null, currentPkg);
        }
        return packageCache.computeIfAbsent(pkgId, this::loadPackage);
    }

    private DamlLf1.Type resolveType(DamlLf1.Package pkg, DamlLf1.Type ty) {
        if (ty.getSumCase() == DamlLf1.Type.SumCase.INTERNED) {
            return pkg.getInternedTypes(ty.getInterned());
        }
        return ty;
    }

    private DamlLf2.Type resolveType(DamlLf2.Package pkg, DamlLf2.Type ty) {
        if (ty.getSumCase() == DamlLf2.Type.SumCase.INTERNED) {
            return pkg.getInternedTypes(ty.getInterned());
        }
        return ty;
    }

    /** Provide full AST (record fields) for template create arguments. */
    public TemplateAst getTemplateAst(String packageId, String moduleName, String entityName) {
        PackageWrapper pkg = packageCache.computeIfAbsent(packageId, this::loadPackage);
        if (pkg == null) {
            throw new IllegalStateException("Package not found or failed to load");
        }
        if (pkg.lf2 != null) {
            return getTemplateAstLf2(pkg.lf2, moduleName, entityName);
        }
        return getTemplateAstLf1(pkg.lf1, moduleName, entityName);
    }

    private TemplateAst getTemplateAstLf1(DamlLf1.Package pkg, String moduleName, String entityName) {
        var module = pkg.getModulesList().stream()
                .filter(m -> moduleName.equals(resolveModuleName(pkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleName));

        var template = module.getTemplatesList().stream()
                .filter(t -> entityName.equals(resolveTemplateName(pkg, t)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + entityName));

        // The template create arguments are defined by the data type referenced by tycon (within the same module).
        String dataName;
        if (template.hasTyconDname()) {
            dataName = String.join(".", template.getTyconDname().getSegmentsList());
        } else if (template.hasTyconInternedDname()) {
            dataName = dottedNameFromInterned(pkg, template.getTyconInternedDname());
        } else {
            throw new IllegalStateException("Template has no tycon");
        }

        DamlLf1.DefDataType dt = module.getDataTypesList().stream()
                .filter(dt1 -> dataName.equals(resolveDottedName(pkg, dt1)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data type not found for template: " + dataName));

        if (!dt.hasRecord()) {
            throw new IllegalStateException("Template argument is not a record");
        }

        return new TemplateAst(pkg, null, dt.getRecord().getFieldsList(), null);
    }

    public DamlLf1.DefDataType getDataType(DamlLf1.Package pkg, DamlLf1.TypeConName name) {
        DamlLf1.ModuleRef moduleRef = name.getModule();
        String modName = resolveModuleRef(pkg, moduleRef);
        PackageWrapper target = resolvePackageRef(pkg, moduleRef);
        if (target == null || target.lf1 == null) {
            throw new IllegalArgumentException("Package not found for type: " + modName);
        }
        DamlLf1.Package targetPkg = target.lf1;
        String dataName = switch (name.getNameCase()) {
            case NAME_DNAME -> String.join(".", name.getNameDname().getSegmentsList());
            case NAME_INTERNED_DNAME -> dottedNameFromInterned(pkg, name.getNameInternedDname());
            case NAME_NOT_SET -> "<name>";
        };
        var module = targetPkg.getModulesList().stream()
                .filter(m -> modName.equals(resolveModuleName(targetPkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found for type: " + modName));
        return module.getDataTypesList().stream()
                .filter(dt -> dataName.equals(resolveDottedName(targetPkg, dt)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data type not found: " + dataName));
    }

    private TemplateAst getTemplateAstLf2(DamlLf2.Package pkg, String moduleName, String entityName) {
        var module = pkg.getModulesList().stream()
                .filter(m -> moduleName.equals(resolveModuleName(pkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleName));

        var template = module.getTemplatesList().stream()
                .filter(t -> entityName.equals(resolveTemplateName(pkg, t)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + entityName));

        String dataName = dottedNameFromInterned(pkg, template.getTyconInternedDname());
        var dataType = module.getDataTypesList().stream()
                .filter(dt -> dataName.equals(resolveDottedName(pkg, dt)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data type not found: " + dataName));

        if (!dataType.hasRecord()) {
            throw new IllegalStateException("Template argument is not a record");
        }

        return new TemplateAst(null, pkg, null, dataType.getRecord().getFieldsList());
    }

    public DamlLf2.DefDataType getDataType(DamlLf2.Package pkg, DamlLf2.TypeConName name) {
        DamlLf2.ModuleRef moduleRef = name.getModule();
        String modName = resolveModuleRef(pkg, moduleRef);
        PackageWrapper target = resolvePackageRef(pkg, moduleRef);
        if (target == null || target.lf2 == null) {
            throw new IllegalArgumentException("Package not found for type: " + modName);
        }
        DamlLf2.Package targetPkg = target.lf2;
        String dataName = dottedNameFromInterned(pkg, name.getNameInternedDname());
        var module = targetPkg.getModulesList().stream()
                .filter(m -> modName.equals(resolveModuleName(targetPkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found for type: " + modName));
        return module.getDataTypesList().stream()
                .filter(dt -> dataName.equals(resolveDottedName(targetPkg, dt)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data type not found: " + dataName));
    }

    public ResolvedDataTypeLf1 resolveDataTypeLf1(DamlLf1.Package pkg, DamlLf1.TypeConName name) {
        DamlLf1.ModuleRef moduleRef = name.getModule();
        String modName = resolveModuleRef(pkg, moduleRef);
        PackageWrapper target = resolvePackageRef(pkg, moduleRef);
        if (target == null || target.lf1 == null) {
            throw new IllegalArgumentException("Package not found for type: " + modName);
        }
        DamlLf1.Package targetPkg = target.lf1;
        String dataName = switch (name.getNameCase()) {
            case NAME_DNAME -> String.join(".", name.getNameDname().getSegmentsList());
            case NAME_INTERNED_DNAME -> dottedNameFromInterned(pkg, name.getNameInternedDname());
            case NAME_NOT_SET -> "<name>";
        };
        var module = targetPkg.getModulesList().stream()
                .filter(m -> modName.equals(resolveModuleName(targetPkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found for type: " + modName));
        var dt = module.getDataTypesList().stream()
                .filter(d -> dataName.equals(resolveDottedName(targetPkg, d)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data type not found: " + dataName));
        return new ResolvedDataTypeLf1(targetPkg, dt);
    }

    public ResolvedDataTypeLf2 resolveDataTypeLf2(DamlLf2.Package pkg, DamlLf2.TypeConName name) {
        DamlLf2.ModuleRef moduleRef = name.getModule();
        String modName = resolveModuleRef(pkg, moduleRef);
        PackageWrapper target = resolvePackageRef(pkg, moduleRef);
        if (target == null || target.lf2 == null) {
            throw new IllegalArgumentException("Package not found for type: " + modName);
        }
        DamlLf2.Package targetPkg = target.lf2;
        String dataName = dottedNameFromInterned(pkg, name.getNameInternedDname());
        var module = targetPkg.getModulesList().stream()
                .filter(m -> modName.equals(resolveModuleName(targetPkg, m)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Module not found for type: " + modName));
        var dt = module.getDataTypesList().stream()
                .filter(d -> dataName.equals(resolveDottedName(targetPkg, d)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data type not found: " + dataName));
        return new ResolvedDataTypeLf2(targetPkg, dt);
    }

    public record FieldInfo(int index, String name, String type) {}

    public record SchemaResponse(
            String packageId,
            String moduleName,
            String entityName,
            String param,
            List<FieldInfo> fields
    ) {}

    public record ChoiceInfo(
            String name,
            boolean consuming,
            String argType,
            List<FieldInfo> argFields
    ) {}

    public record ChoicesResponse(
            String packageId,
            String moduleName,
            String entityName,
            List<ChoiceInfo> choices
    ) {}

    public record TemplateAst(
            DamlLf1.Package lf1Package,
            DamlLf2.Package lf2Package,
            List<DamlLf1.FieldWithType> lf1Fields,
            List<DamlLf2.FieldWithType> lf2Fields
    ) {
        public boolean isLf1() {
            return lf1Package != null;
        }

        public boolean isLf2() {
            return lf2Package != null;
        }
    }

    public record DataTypeAst(
            DamlLf1.Package lf1Package,
            DamlLf2.Package lf2Package,
            List<DamlLf1.FieldWithType> lf1Fields,
            List<DamlLf2.FieldWithType> lf2Fields
    ) {
        public boolean isLf1() {
            return lf1Package != null;
        }

        public boolean isLf2() {
            return lf2Package != null;
        }
    }

    public record ChoiceAst(
            String name,
            boolean consuming,
            DamlLf1.Type lf1ArgType,
            DamlLf2.Type lf2ArgType
    ) { }

    public record TemplateChoicesAst(
            DamlLf1.Package lf1Package,
            DamlLf2.Package lf2Package,
            List<ChoiceAst> choices
    ) {
        public boolean isLf1() {
            return lf1Package != null;
        }

        public boolean isLf2() {
            return lf2Package != null;
        }
    }

    private record PackageWrapper(DamlLf1.Package lf1, DamlLf2.Package lf2) { }

    public record ResolvedDataTypeLf1(DamlLf1.Package pkg, DamlLf1.DefDataType def) { }

    public record ResolvedDataTypeLf2(DamlLf2.Package pkg, DamlLf2.DefDataType def) { }
}

