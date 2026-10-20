package cn.zbx1425.mtrsteamloco.scripting;

import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;

/**
 * [续更] JS 脚本沙箱文件系统：以指定目录为根的"监狱"文件系统。
 * <p>
 * 替代 GraalVM 24.x 起不再公开的内部 API
 * {@code com.oracle.truffle.polyglot.FileSystems.newDefaultFileSystem(String root)}
 * （原语义：以 root 为工作目录根，脚本只能访问其内部路径）。
 * 本实现委托默认 NIO 文件系统，并把所有路径限制在根目录内：
 * 相对路径锚定根目录；绝对路径若越出根目录直接拒绝；
 * 符号链接解析后的真实路径同样必须留在根目录内。
 * 全部使用公开 API（org.graalvm.polyglot.io.FileSystem + java.nio.file）。
 */
public final class RootedScriptFileSystem implements FileSystem {

    private final Path root;

    public RootedScriptFileSystem(Path root) {
        // [修复 2026-10-21] 原为 root.toAbsolutePath().normalize()。
        // guard() 里 real 是 toRealPath()（已解引用符号链接）的路径，
        // 而 root 未解引用 ⇒ 若 root 自身路径含符号链接，
        // 合法路径会被误判越界（AccessDeniedException），
        // 表现为「沙箱里明明有文件却读不到」。
        // 改为对 root 也做 toRealPath()；root 不存在时退回 normalize()。
        Path abs = root.toAbsolutePath().normalize();
        Path real;
        try {
            real = abs.toRealPath();
        } catch (IOException e) {
            real = abs;
        }
        this.root = real;
    }

    /** 路径解引用（含符号链接）后是否仍在根目录内；越界抛 AccessDeniedException */
    private Path guard(Path path) throws IOException {
        Path abs;
        if (path.isAbsolute()) {
            abs = path.normalize();
        } else {
            abs = root.resolve(path).normalize();
        }
        Path real;
        try {
            real = abs.toRealPath();
        } catch (IOException e) {
            // 目标可能尚不存在（新建文件场景）：对最近的存在祖先做 real path 校验
            Path parent = abs.getParent();
            Path probe = abs;
            while (parent != null && !Files.exists(parent)) {
                probe = parent;
                parent = parent.getParent();
            }
            try {
                real = probe.toRealPath();
                if (!Files.isDirectory(real) && Files.exists(real)) {
                    // probe 是已存在的文件而非缺失目录，退回 abs 交由后续操作报错
                    real = abs;
                }
            } catch (IOException e2) {
                real = abs;
            }
        }
        if (!real.startsWith(root)) {
            throw new AccessDeniedException(path.toString(), null, "outside script data root");
        }
        return abs;
    }

    @Override
    public Path parsePath(java.net.URI uri) {
        throw new UnsupportedOperationException("URI paths are not allowed in script sandbox");
    }

    @Override
    public Path parsePath(String path) {
        return Paths.get(path);
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        Path p = guard(path);
        // Files.checkAccess 为 JDK 内部 API，改用公开的属性探测实现
        if (modes.contains(AccessMode.READ) && !Files.isReadable(p)) {
            throw new AccessDeniedException(p.toString());
        }
        if (modes.contains(AccessMode.WRITE) && !Files.isWritable(p)) {
            throw new AccessDeniedException(p.toString());
        }
        if (modes.contains(AccessMode.EXECUTE) && !Files.isExecutable(p)) {
            throw new AccessDeniedException(p.toString());
        }
    }

    @Override
    public void createDirectory(Path dir, java.nio.file.attribute.FileAttribute<?>... attrs) throws IOException {
        Files.createDirectories(guard(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.delete(guard(path));
    }

    @Override
    public java.nio.channels.SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, java.nio.file.attribute.FileAttribute<?>... attrs) throws IOException {
        return Files.newByteChannel(guard(path), options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        final Path p = guard(dir);
        final DirectoryStream<Path> delegate = Files.newDirectoryStream(p, filter);
        return new DirectoryStream<Path>() {
            @Override
            public Iterator<Path> iterator() {
                return delegate.iterator();
            }
            @Override
            public void close() throws IOException {
                delegate.close();
            }
        };
    }

    @Override
    public Path toAbsolutePath(Path path) {
        if (path.isAbsolute()) return path.normalize();
        return root.resolve(path).normalize();
    }

    @Override
    public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
        return guard(path).toRealPath(linkOptions);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return Files.readAttributes(guard(path), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        Files.setAttribute(guard(path), attribute, value, options);
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        // 越界守卫在 guard() 内完成（含符号链接解引用），直接委托
        Files.copy(guard(source), guard(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        Files.move(guard(source), guard(target), options);
    }

    @Override
    public String getSeparator() {
        return java.io.File.separator;
    }

    @Override
    public Path getTempDirectory() {
        return root.resolve("tmp");
    }
}
