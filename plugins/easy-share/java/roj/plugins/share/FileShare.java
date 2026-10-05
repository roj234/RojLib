package roj.plugins.share;

import org.jetbrains.annotations.Nullable;
import roj.asm.Opcodes;
import roj.collect.*;
import roj.concurrent.TimerTask;
import roj.config.ConfigMaster;
import roj.config.JsonSerializer;
import roj.config.mapper.ObjectMapper;
import roj.config.node.MapValue;
import roj.crypt.Base64;
import roj.crypt.CryptoFactory;
import roj.http.Cookie;
import roj.http.server.*;
import roj.http.server.auto.*;
import roj.io.IOUtil;
import roj.plugin.PermissionHolder;
import roj.plugin.Plugin;
import roj.plugin.PluginDescriptor;
import roj.text.CharList;
import roj.text.HtmlEntities;
import roj.text.ParseException;
import roj.text.TextUtil;
import roj.ui.Argument;
import roj.util.TypedKey;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static roj.ui.CommandNode.argument;
import static roj.ui.CommandNode.literal;

/**
 * @author Roj234
 * @since 2025/3/8 0:39
 */
@Mime("application/json")
public class FileShare extends Plugin {
    static final int LOCAL_FILE_MAX = 100000;
    static final int REMOTE_FILE_MAX = 1000;

    static final class Serialized {
        List<Share> shares;
        int shareFileIndex;

        public Serialized() {}
        public Serialized(FileShare share) {
            shares = new ArrayList<>(share.shares);
            shareFileIndex = share.shareFileIdx.get();
        }
    }

    private static final int MAX_NAME_LENGTH = 30;
    private static final int FILE_UPLOAD_TASKS = 4;

    private final Object lock = new Object();
    private final long idMagic = System.nanoTime() ^ (long) System.identityHashCode(this) << 32;

    private static final XashMap.Template<String, Share> SHARE_INFO_TEMPLATE = XashMap.forType(String.class, Share.class).key("id").build();
    private XashMap<String, Share> shares = SHARE_INFO_TEMPLATE.create();
    private final XashMap<String, Share> incompleteShares = SHARE_INFO_TEMPLATE.create();

    File uploadPath;
    private final AtomicInteger shareFileIdx = new AtomicInteger(1);
    private final ChunkUpload uploadManager = new ChunkUpload();

    private final ObjectMapper ownerSerializer;
    private boolean dirty;

    private Plugin sso;

    public FileShare() {
        ObjectMapper.SerializeSetting transientRemover = (owner, field, annotations) -> {
            if (field != null) {
                String name = field.name();
                if (name.equals("_next") || name.equals("file") || name.equals("uploading") || name.equals("vfs")) return annotations;
                field.modifier &= ~Opcodes.ACC_TRANSIENT;
            }
            return annotations;
        };
        ownerSerializer = ObjectMapper.getInstance().serializeFileToString().registerType(ShareFile.class, transientRemover).registerType(Share.class, transientRemover);
    }

    @Override
    protected void onEnable() throws Exception {
        sso = Objects.requireNonNull(getPluginManager().getPluginInstance(PluginDescriptor.Role.PermissionManager), "找不到权限管理插件");

        uploadPath = new File(getDataFolder(), "files");
        uploadPath.mkdir();

        MapValue config = getConfig();
        uploadManager.setFragmentSize(config.getInt("fragmentSize", 4194304));

        reloadDB();
        getScheduler().loop(() -> {
            // 每5分钟清除1小时之前开始的上传任务
            uploadManager.purge(System.currentTimeMillis() - 3600000);
            // 如果一个共享5分钟内没有上传新文件，那么删除它
            synchronized (incompleteShares) {
				for (var itr = incompleteShares.iterator(); itr.hasNext(); ) {
					var share = itr.next();

                    var set = share.uploading;
                    if (set != null && set.isEmpty()) {
						if (share.view != share.files.size()) {
							share.view = share.files.size();
						} else {
                            getLogger().warn("删除超时未完成的共享{}", share.id);
							itr.remove();
						}
					}
				}
            }
        }, 300000);

        registerCommand(literal("fileshare")
                .then(literal("remove").breakOn().then(argument("网页路径", Argument.oneOf(CollectionX.toMap(shares))).executes(ctx -> {
                    removeShare(ctx.argument("网页路径", Share.class));
                })))
                .then(literal("reload").breakOn().executes(ctx -> {
                    synchronized (lock) {
                        reloadDB();
                        if (saveLater != null) saveLater.cancel();
                        dirty = false;
                    }
                    System.out.println("已重载");
                }))
                .then(argument("网页路径", Argument.string()).then(argument("文件路径", Argument.path()).executes(ctx -> {
                    String id = ctx.argument("网页路径", String.class);
                    File path = ctx.argument("文件路径", File.class);
                    var share = new Share();
                    share.id = id;
                    share.name = "文件分享";
                    share.time = System.currentTimeMillis();
                    share.fillFromServerPath(path);
                    if (share.files.size() == LOCAL_FILE_MAX) getLogger().warn("文件数量超过了"+LOCAL_FILE_MAX+"，请通过在线预览访问全部项目");

                    synchronized (lock) {
                        if (!shares.add(share)) {
                            getLogger().warn("直链/share/{}已存在", id);
                            return;
                        }
                        markDirty();
                    }
                    getLogger().info("直链/share/{}注册成功", id);
                })))
        );

        registerRoute(config.getString("path"), new OKRouter().addPrefixDelegation("/", new ZipRouter(getDescription().getArchive(), "web/")).register(this), "PermissionManager");
    }

    private void reloadDB() throws IOException, ParseException {
        var persist = new File(getDataFolder(), "db.yml");
        if (persist.isFile()) {
            var db = ownerSerializer.reader(Serialized.class).read(persist, ConfigMaster.YAML);
            shares = SHARE_INFO_TEMPLATE.createValued(db.shares);
            shareFileIdx.set(db.shareFileIndex);
        } else {
            shares = SHARE_INFO_TEMPLATE.create();
        }
    }

    private void removeShare(Share info) {
        synchronized (lock) {
            if (!shares.remove(info)) return;
            markDirty();
        }
        getLogger().info("移除了直链/share/{}", info);
    }

    @Override
    protected void onDisable() {
        save();
        uploadManager.purge(System.currentTimeMillis());
    }

    private TimerTask saveLater;
    private void markDirty() {
        dirty = true;
        if (saveLater != null) saveLater.cancel();
        saveLater = getScheduler().delay(this::save, 30000);
    }

    public void save() {
        Serialized snapshot;
        synchronized (lock) {
            if (!dirty) return;
            snapshot = new Serialized(this);
            dirty = false;
        }

        try {
            IOUtil.writeAtomically(getDataFolder(), "db.yml", file -> {
                ownerSerializer.writer(Serialized.class).write(ConfigMaster.YAML, snapshot, file);
                getLogger().debug("配置已保存");
            });
        } catch (IOException e) {
            markDirty();
            getLogger().error("配置文件保存失败", e);
        }
    }

    private Share getUnexpired(Request req) {
        Share info = shares.get(req.argument("shareId"));
        if (info != null && info.isExpired()) {
            synchronized (lock) {
                if (!shares.remove(info)) return null;
                markDirty();
            }
            deleteShare(info);
            return null;
        }

        return info;
    }

    //region Get
    @GET(":shareId/")
    public Content index(Request req) {
        var zip = getDescription().getArchive();
        return Content.file(req, new ZipRouter.ZipFileInfo(zip, zip.getEntry("share.html")));
    }

    @GET(":shareId/explore/**")
    public Content shareExplore(Request req) throws IOException {
        return Content.httpError(502);
    }

    @Route(":shareId/info")
    @Accepts(Accepts.GET|Accepts.POST)
    public CharSequence shareInfo(Request req) throws IllegalRequestException {
        var info = getUnexpired(req);
        if (info == null) return "{\"ok\":false,\"data\":\"你来的太晚，分享已经被取消了\"}";

        ok:
        if (info.code != null) {
            if (checkShareCode(req, info)) break ok;

            if (info.code.isEmpty() || !info.code.equals(req.formData().get("code"))) return "{\"ok\":false,\"data\":\"文件被密码保护\",\"exist\":true}";

            String token;
            if (info.expireType() == 2) {
                long timeout = System.currentTimeMillis() + 1800000;
                info.countDown(timeout);
                dirty = true;

                int salt = ThreadLocalRandom.current().nextInt() * 1145141919;
                int hash = CryptoFactory.xxHash32(info.hashCode() ^ salt, IOUtil.getSharedByteBuf().putLong(timeout).array(), 0, 8);
                token = IOUtil.getSharedByteBuf().putInt(salt).putLong(timeout).putInt(hash).base64UrlSafe();
            } else {
                token = info.code;
            }

            info.view++;
            dirty = true;
            req.setCookie(new Cookie("code", token).expires(0));
        }

        return ObjectMapper.SAFE.write(ConfigMaster.JSON, info, new CharList().append("{\"ok\":true,\"data\":")).append('}');
    }

    private boolean checkShareCode(Request req, Share info) throws IllegalRequestException {
        String cookieCode = req.cookies().getOrDefault("code", "");
        if (info.expireType() != 2 && !info.code.isEmpty()) return info.code.equals(cookieCode);

        var token = Base64.decode(cookieCode, IOUtil.getSharedByteBuf(), Base64.B64_URL_SAFE_REV);
        if (token.readableBytes() != 16) return false;

        int salt = token.readInt();
        int hash = CryptoFactory.xxHash32(info.hashCode() ^ salt, token.array(), 4, 8);
        long time = token.readLong();
        int exceptHash = token.readInt();

		return hash == exceptHash && System.currentTimeMillis() < time;
	}

    @GET(":shareId/file/:fileId(\\d+)")
    public Content downloadFile(Request req) throws IllegalRequestException {
        Share info = getUnexpired(req);
        if (info == null) return Content.httpError(404);

        if (info.code != null && !checkShareCode(req, info)) {
            req.response().code(403);
            return Content.text("提取码不正确");
        }

        List<ShareFile> files = info.files;
        int fileId = Integer.parseInt(req.argument("fileId"));
        if (fileId >= files.size()) return Content.httpError(404);

        var file = files.get(fileId);

        info.download++;
        dirty = true;
        //file.download++;
        var realFile = file.file;
        if (realFile == null) {
            realFile = file.id != 0
                    ? new File(uploadPath, Integer.toString(file.id, 36))
                    : new File(info.base, (file.path == null ? "" : file.path + '/') + file.name);
            file.file = realFile;
        }
        return Content.file(req, new DiskFileInfo(realFile));
    }
    //endregion

    @POST(":shareId/delete")
    public String shareDelete(Request req) {
        Share info = getUnexpired(req);
        if (info == null || !isOwner(req, info)) return "{\"ok\":false,\"data\":\"不存在或不是你的共享\"}";
        boolean flag;
        synchronized (lock) {
            flag = shares.remove(info);
            if (flag) markDirty();
        }
		if (flag) deleteShare(info);

        return "{\"ok\":true,\"data\":\"已删除\"}";
    }

    private void deleteShare(Share info) {
        if (info.files != null) {
            for (ShareFile file : info.files) {
                if (file.id == 0) continue;

                var realFile = new File(uploadPath, Integer.toString(file.id, 36));
                if (!realFile.delete() && realFile.isFile()) {
                    getLogger().warn("无法删除文件 {}", realFile);
                    getScheduler().loop(realFile::delete, 1000, 10);
                }
            }
        }
    }

    @GET("list")
    public CharSequence shareList(Request req) {
        var user = getUser(req);
        var simpleSer = new JsonSerializer();
        simpleSer.emitMap();
        simpleSer.emitKey("ok");
        simpleSer.emit(true);
        simpleSer.emitKey("data");
        simpleSer.emitList();
        if (user != null) {
            List<Share> myShares = new ArrayList<>();
            synchronized (lock) {
                for (var info : shares) {
                    if (info.owner == user.getId()) myShares.add(info);
                }
            }
            // TIME DESC
            myShares.sort((o1, o2) -> Long.compare(o2.time, o1.time));
            for (int i = 0; i < myShares.size(); i++) {
                writeInfoHistory(myShares.get(i), simpleSer);
                simpleSer.pop();
            }
        }
        return simpleSer.getValue();
    }

    private static CharList writeInfoHistory(Share share, boolean addThreads) {
        var simpleSer = new JsonSerializer();
        simpleSer.emitMap();
        simpleSer.emitKey("ok");
        simpleSer.emit(true);
        simpleSer.emitKey("data");
        writeInfoHistory(share, simpleSer);
        if (addThreads) {
            simpleSer.emitKey("threads");
            simpleSer.emit(FILE_UPLOAD_TASKS);
            simpleSer.emitKey("maxFiles");
            simpleSer.emit(REMOTE_FILE_MAX);
        }
        simpleSer.pop();
        return simpleSer.getValue();
    }
    private static void writeInfoHistory(Share info, JsonSerializer ser) {
        ser.emitMap();
        ser.emitKey("id");
        ser.emit(info.id);
        ser.emitKey("name");
        ser.emit(info.name);
        if (info.code != null) {
            ser.emitKey("code");
            ser.emit(info.code);
        }
        ser.emitKey("time");
        ser.emit(info.time);
        ser.emitKey("view");
        ser.emit(info.view);
        ser.emitKey("download");
        ser.emit(info.download);

        String name, type;
        var file = info.files;
        if (file == null) {
            name = "";
            type = "text/";
        } else {
            if (file.isEmpty()) {
                ser.pop();
                return;
            }

            if (file.size() == 1) {
                name = file.get(0).name;
                type = file.get(0).mime;
            } else {
                name = "";
                type = "folder";
            }
        }

        ser.emitKey("size");
        ser.emit(info.size);
        ser.emitKey("expire");
        ser.emit(info.expire);

        ser.emitKey("file");
        ser.emitMap();
        ser.emitKey("name");
        ser.emit(name);
        ser.emitKey("type");
        ser.emit(type);
        ser.pop();
    }

    //region new
    @Interceptor
    public void newHandler(Request req, PayloadInfo ps) {
        if (ps != null) ps.accept(110000, 1000);
    }

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9-_@]+$");
    @POST("new/")
    @Interceptor("newHandler")
    public CharSequence shareCreate(Request req) throws IllegalRequestException {
        Map<String, String> map = req.formData();

        var user = getUser(req);
        if (user == null) return error("登录以上传文件");

        String id = map.getOrDefault("id", "").trim();
        if (id.isEmpty()) {
            long time = System.nanoTime();
            long rand = ThreadLocalRandom.current().nextLong();
			while (true) {
				// 先异或后乘，破坏时间戳与随机数的关联性
				long magicId = (time ^ rand) * (rand ^ idMagic); // 魔数增加扰动
				// 需要注意的是，这并不密码学安全，但是防止遍历足够了
				id = IOUtil.getSharedByteBuf().putLong(magicId).base64UrlSafe();
				if (!id.startsWith("public") && !shares.containsKey(id)) break;
				rand = ThreadLocalRandom.current().nextLong();
			}
        }
        else {
            if (!user.hasPermission("share/custom_id")) return error("您没有自定义链接的权限");
            if (id.length() < 6 || id.length() > MAX_NAME_LENGTH || !ID_PATTERN.matcher(id).matches()) return error("参数错误");
        }

        if (!user.hasPermission(id.startsWith("public") ? "share/public" : "share")) return error("您没有创建分享的权限");

        var info = shares.get(id);
        if (info != null || incompleteShares.containsKey(id)) return error("自定义链接已存在");

        var code = map.getOrDefault("code", "").trim();
        if (code.isEmpty()) {
            int time = (int) System.nanoTime();
            int rand = ThreadLocalRandom.current().nextInt();
            int magicId = (time ^ rand) * (rand ^ 0xb418e3dd);
            code = Integer.toUnsignedString(Math.abs(magicId) % 1679616/*pow(36, 4)*/, 36);
            if (code.length() < 4) code = "0".repeat(4-code.length()).concat(code);
        }
        else {
            if (!user.hasPermission("share/custom_code")) return error("您没有自定义提取码的权限");
            if (code.length() < 4 || code.length() > 12) return error("参数错误");
            else if (code.equals("none")) code = null;
        }

        info = new Share();
        info.id = id;
        info.owner = user.getId();
        String name = map.getOrDefault("name", "").trim();
        if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH);
        info.name = name.isEmpty() ? null : HtmlEntities.encode(name).toString();
        info.code = code;
        info.time = System.currentTimeMillis();

        var type = map.getOrDefault("type", "file");

        String expire = map.getOrDefault("expire", "1");
        if (TextUtil.isNumber(expire) == 0) {
            info.expire = Long.parseLong(expire);
            if (info.expire < 1000000000000L) {
                if (info.expire <= 0 || info.expire > 10000) return error("参数错误");
                if (code == null) return error("公开分享无法使用计次过期");
                if (!user.hasPermission("share/expire/count")) return error("您没有创建计次分享的权限");
            } else {
                long expireTime = info.expire - System.currentTimeMillis();
                if (expireTime < 1800000) return error("过期时间不足半小时");
            }
        } else if (!expire.equals("never")) {
            if (!user.hasPermission("share/expire/never")) return error("您没有创建永久分享的权限");
            return error("参数错误");
        }

        if (!type.equals("file")) {
            info.text = map.get("text");
            if (info.text == null) {
                if (!user.hasPermission("share/path")) return error("您没有创建目录分享的权限");
                File path = new File(map.getOrDefault("path", ""));
                if (!isReadable(req, path)) return error("您没有该目录的读取权限");
                info.fillFromServerPath(path);

                if (info.name == null) {
                    info.name = "服务器上的"+path.getName();
                }
            } else {
                info.size = (long) info.text.length() << 1;

                if (info.name == null) {
                    int i = info.text.indexOf('\n');
                    if (i > MAX_NAME_LENGTH) i = MAX_NAME_LENGTH;
                    else if (i < 0) i = Math.min(info.text.length(), MAX_NAME_LENGTH);
                    info.name = info.text.substring(0, i);
                }
            }

            synchronized (lock) {
                if (!shares.add(info)) return error("自定义链接已存在");
                markDirty();
            }
        } else {
            if (!user.hasPermission("share/file")) return error("您没有创建文件分享的权限");
            info.files = new ArrayList<>();
            info.uploading = new HashSet<>(Hasher.identity());
            synchronized (incompleteShares) {
                if (!incompleteShares.add(info)) return error("自定义链接已存在");
            }
        }

        return writeInfoHistory(info, info.files != null);
    }
    private static String error(String error) {return "{\"ok\":false,\"data\":\""+error+"\"}";}

    @POST(":shareId/add")
    public Content shareNewUploadTask(Request req, String name, @QueryParam(orDefault = "") String path, long size, long lastModified) {
        var shareInfo = incompleteShares.get(req.argument("shareId"));
        if (shareInfo != null) {
            var file = new ShareFile();
            file.name = name;
            if (!path.isEmpty())
                file.path = path;
            file.size = size;
            file.lastModified = lastModified;
            file.mime = MimeType.getMimeType(IOUtil.getExtension(name));


            synchronized (shareInfo) {
                var uploading = shareInfo.uploading;
                if (uploading == null || uploading.size() > FILE_UPLOAD_TASKS) return Content.json("{\"ok\":false,\"data\":\"线程数超过限制\"}");

                if (shareInfo.files.size() > REMOTE_FILE_MAX) return Content.json("{\"ok\":false,\"data\":\"一次分享的文件太多了\"}");
                shareInfo.size += size;
                shareInfo.files.add(file);
            }

            int attachmentId = shareFileIdx.getAndIncrement();
            file.id = attachmentId;

            if (size == 0) {
                file.id = -1;
                return Content.json("{\"ok\":false,\"id\":-1}");
            }

            return uploadManager.newTask(uploadPath, Integer.toString(attachmentId, 36), size, (task, ok) -> {
                var uploading = shareInfo.uploading;
                if (uploading != null) {
                    synchronized (shareInfo) {
                        if (ok == null) uploading.add(task);
                        else uploading.remove(task);
                    }
                }
            });
        }

        return Content.json("{\"ok\":false,\"data\":\"参数错误\"}");
    }

    @GET("upload/:taskId/status")
    public CharSequence shareUploadFile(Request req) throws IllegalRequestException {return uploadManager.getUploadStatus(req);}

    @POST("upload/:taskId/:fragment(\\d{1,8})")
    @Interceptor("uploadProcessor")
    public void shareUploadFile() {}

    @Interceptor
    public void uploadProcessor(Request req, PayloadInfo setting) throws IOException {uploadManager.uploadProcessor(req, setting);}

    @POST(":shareId/submit")
    public CharSequence sharePackup(Request req) {
        String id = req.argument("shareId");
        Share share;
        synchronized (incompleteShares) {
            share = incompleteShares.removeKey(id);
        }

        block:
        if (share != null) {
            synchronized (share) {
                var set = share.uploading;
                share.uploading = null;
                for (var task : set) {
                    try {
                        task.finish(false);
                    } catch (IOException e) {
                        getLogger().warn("中止上传任务失败", e);
                    }
                }
            }

            share.view = 0;
            if (share.name == null) {
                share.name = share.files.size() == 1 ? share.files.get(0).name : share.files.get(0).name+"等"+share.files.size()+"个文件";
            }

            synchronized (lock) {
                if (!shares.add(share)) break block;
                markDirty();
            }

            return writeInfoHistory(share, false);
        }

        return "{\"ok\":false}";
    }
    //endregion

    // 权限控制
    @Nullable private PermissionHolder getUser(Request req) {return sso.ipc(new TypedKey<>("getUser"), req);}
    private boolean isOwner(Request req, Share info) {var user = getUser(req);return user != null && user.getId() == info.owner;}
    private boolean isReadable(Request req, File path) {var user = getUser(req);return user != null && user.isReadable(path.getAbsolutePath());}
}