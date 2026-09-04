package com.km.skillhub.service;

import com.km.skillhub.asset.model.vo.SkillPackageValidationVO;
import com.km.skillhub.asset.service.SkillPackageValidationException;
import com.km.skillhub.asset.service.SkillPackageValidator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillPackageValidationTest {
    private final SkillPackageValidator validator = new SkillPackageValidator(
            1024 * 1024, 2 * 1024 * 1024, 1024 * 1024, 100, 255);

    @Test
    void computesStableVersionDigestIndependentOfZipOrder() throws Exception {
        byte[] first = zip(new String[][]{{"SKILL.md", skillMarkdown()}, {"scripts/run.js", "ok"}});
        byte[] second = zip(new String[][]{{"scripts/run.js", "ok"}, {"SKILL.md", skillMarkdown()}});

        SkillPackageValidationVO firstResult = validator.validate(first);
        SkillPackageValidationVO secondResult = validator.validate(second);

        assertNotEquals(firstResult.getArtifactDigest(), secondResult.getArtifactDigest());
        assertEquals(firstResult.getVersionDigest(), secondResult.getVersionDigest());
        assertEquals("demo-skill", firstResult.getName());
        assertEquals("1.2.3", firstResult.getVersionLabel());
        assertEquals(2, firstResult.getManifest().size());
    }

    @Test
    void rejectsInvalidMetadataAndDuplicatePaths() throws Exception {
        SkillPackageValidationException invalidMetadata = assertThrows(SkillPackageValidationException.class,
                () -> validator.validate(zip(new String[][]{{"SKILL.md",
                        "---\nname: demo\ndescription: Demo\nversion: latest\n---\n"}})));
        assertEquals("INVALID_SKILL_METADATA", invalidMetadata.getCode());

        SkillPackageValidationException duplicate = assertThrows(SkillPackageValidationException.class,
                () -> validator.validate(duplicatePathZip()));
        assertEquals("DUPLICATE_PACKAGE_PATH", duplicate.getCode());
    }

    @Test
    void rejectsTraversalAndExpandedSizeBeforePersistence() throws Exception {
        SkillPackageValidationException traversal = assertThrows(SkillPackageValidationException.class,
                () -> validator.validate(zip(new String[][]{{"SKILL.md", skillMarkdown()}, {"../secret", "x"}})));
        assertEquals("UNSAFE_PACKAGE_PATH", traversal.getCode());

        SkillPackageValidator smallValidator = new SkillPackageValidator(1024 * 1024, 4, 4, 100, 255);
        SkillPackageValidationException oversized = assertThrows(SkillPackageValidationException.class,
                () -> smallValidator.validate(zip(new String[][]{{"SKILL.md", skillMarkdown()}})));
        assertEquals("PACKAGE_FILE_TOO_LARGE", oversized.getCode());
    }

    @Test
    void rejectsMalformedUtf8SkillDescription() throws Exception {
        byte[] markdown = skillMarkdown().getBytes(StandardCharsets.UTF_8);
        byte[] marker = "Demo skill".getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset <= markdown.length - marker.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < marker.length; index++) {
                if (markdown[offset + index] != marker[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                markdown[offset] = (byte) 0xC3;
                markdown[offset + 1] = (byte) 0x28;
                break;
            }
        }
        byte[] archive = zip(new String[][]{{"README.md", "text"}}, markdown);

        SkillPackageValidationException exception = assertThrows(SkillPackageValidationException.class,
                () -> validator.validate(archive));

        assertEquals("INVALID_SKILL_ENCODING", exception.getCode());
    }

    private byte[] zip(String[][] entries) throws Exception {
        return zip(entries, null);
    }

    private byte[] zip(String[][] entries, byte[] skillMarkdown) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output);
        if (skillMarkdown != null) {
            ZipEntry skillEntry = new ZipEntry("SKILL.md");
            skillEntry.setTime(entries.length * 1000L);
            zip.putNextEntry(skillEntry);
            zip.write(skillMarkdown);
            zip.closeEntry();
        }
        for (String[] item : entries) {
            ZipEntry entry = new ZipEntry(item[0]);
            entry.setTime(entries.length * 1000L);
            zip.putNextEntry(entry);
            zip.write(item[1].getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        zip.close();
        return output.toByteArray();
    }

    private byte[] duplicatePathZip() throws Exception {
        byte[] archive = zip(new String[][]{{"SKILL.md", skillMarkdown()},
                {"same.txt", "one"}, {"else.txt", "two"}});
        byte[] source = "else.txt".getBytes(StandardCharsets.UTF_8);
        byte[] target = "same.txt".getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset <= archive.length - source.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < source.length; index++) {
                if (archive[offset + index] != source[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                System.arraycopy(target, 0, archive, offset, target.length);
                offset += source.length - 1;
            }
        }
        return archive;
    }

    private String skillMarkdown() {
        return "---\nname: demo-skill\ndescription: Demo skill\nversion: 1.2.3\n---\n# Demo\n";
    }
}
