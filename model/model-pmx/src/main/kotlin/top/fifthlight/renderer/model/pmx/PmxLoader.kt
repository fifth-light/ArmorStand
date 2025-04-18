package top.fifthlight.renderer.model.pmx

import org.joml.Matrix4f
import org.joml.Vector3f
import top.fifthlight.renderer.model.Accessor
import top.fifthlight.renderer.model.Buffer
import top.fifthlight.renderer.model.BufferView
import top.fifthlight.renderer.model.HumanoidTag
import top.fifthlight.renderer.model.Material
import top.fifthlight.renderer.model.Mesh
import top.fifthlight.renderer.model.Metadata
import top.fifthlight.renderer.model.ModelFileLoader
import top.fifthlight.renderer.model.Node
import top.fifthlight.renderer.model.NodeId
import top.fifthlight.renderer.model.NodeTransform
import top.fifthlight.renderer.model.Primitive
import top.fifthlight.renderer.model.RgbColor
import top.fifthlight.renderer.model.RgbaColor
import top.fifthlight.renderer.model.Scene
import top.fifthlight.renderer.model.Skin
import top.fifthlight.renderer.model.Texture
import top.fifthlight.renderer.model.pmx.format.PmxBone
import top.fifthlight.renderer.model.pmx.format.PmxGlobals
import top.fifthlight.renderer.model.pmx.format.PmxHeader
import top.fifthlight.renderer.model.pmx.format.PmxMaterial
import top.fifthlight.renderer.model.util.readAll
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

class PmxLoadException(message: String) : Exception(message)

// PMX loader.
// Format from https://gist.github.com/felixjones/f8a06bd48f9da9a4539f
object PmxLoader : ModelFileLoader {
    override val extensions = listOf("pmx")
    override val abilities = listOf(ModelFileLoader.Ability.MODEL)

    private val PMX_SIGNATURE = byteArrayOf(0x50, 0x4D, 0x58, 0x20)
    override val probeLength = PMX_SIGNATURE.size
    override fun probe(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() < PMX_SIGNATURE.size) return false
        val signatureBytes = ByteArray(PMX_SIGNATURE.size)
        buffer.get(signatureBytes, 0, PMX_SIGNATURE.size)
        return signatureBytes.contentEquals(PMX_SIGNATURE)
    }

    private val VALID_INDEX_SIZES = listOf(1, 2, 4)

    //                                             POS NORM UV
    private const val BASE_VERTEX_ATTRIBUTE_SIZE = (3 + 3 + 2) * 4

    //                                           JOINT WEIGHT
    private const val SKIN_VERTEX_ATTRIBUTE_SIZE = (4 + 4) * 4
    private const val VERTEX_ATTRIBUTE_SIZE = BASE_VERTEX_ATTRIBUTE_SIZE + SKIN_VERTEX_ATTRIBUTE_SIZE

    private class Context(
        private val basePath: Path
    ) {
        private lateinit var globals: PmxGlobals
        private val decoder by lazy {
            globals.textEncoding.charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        }

        private lateinit var vertexBuffer: ByteBuffer
        private var vertices: Int = -1

        private lateinit var indexBuffer: ByteBuffer
        private lateinit var indexBufferType: Accessor.ComponentType
        private var indices: Int = -1

        private lateinit var textures: List<Texture>
        private lateinit var materials: List<PmxMaterial>
        private lateinit var bones: List<PmxBone>
        private val childBoneMap = mutableMapOf<Int, MutableList<Int>>()
        private val rootBones = mutableListOf<Int>()

        private fun loadRgbColor(buffer: ByteBuffer): RgbColor {
            if (buffer.remaining() < 3 * 4) {
                throw PmxLoadException("Bad file: want to read Vec3 (12 bytes), but only have ${buffer.remaining()} bytes available")
            }
            return RgbColor(
                r = buffer.getFloat(),
                g = buffer.getFloat(),
                b = buffer.getFloat(),
            )
        }

        private fun loadRgbaColor(buffer: ByteBuffer): RgbaColor {
            if (buffer.remaining() < 4 * 4) {
                throw PmxLoadException("Bad file: want to read Vec4 (16 bytes), but only have ${buffer.remaining()} bytes available")
            }
            return RgbaColor(
                r = buffer.getFloat(),
                g = buffer.getFloat(),
                b = buffer.getFloat(),
                a = buffer.getFloat(),
            )
        }

        private fun loadVector3f(buffer: ByteBuffer): Vector3f {
            if (buffer.remaining() < 3 * 4) {
                throw PmxLoadException("Bad file: want to read Vec3 (12 bytes), but only have ${buffer.remaining()} bytes available")
            }
            return Vector3f(buffer.getFloat(), buffer.getFloat(), buffer.getFloat())
        }

        private fun loadSignature(buffer: ByteBuffer) {
            if (buffer.remaining() < PMX_SIGNATURE.size) {
                throw PmxLoadException("Bad file: signature is ${PMX_SIGNATURE.size} bytes, but only ${buffer.remaining()} bytes in buffer")
            }
            if (PMX_SIGNATURE.any { buffer.get() != it }) {
                throw PmxLoadException("Bad PMX signature")
            }
        }

        private fun loadGlobal(buffer: ByteBuffer) = PmxGlobals(
            textEncoding = when (val encoding = buffer.get().toUByte().toInt()) {
                0 -> PmxGlobals.TextEncoding.UTF16LE
                1 -> PmxGlobals.TextEncoding.UTF8
                else -> throw PmxLoadException("Bad text encoding: $encoding")
            },
            additionalVec4Count = buffer.get().toUByte().toInt().also {
                if (it !in 0..4) {
                    throw PmxLoadException("Bad additional vec4 count: $it, should be in [0, 4]")
                }
            },
            vertexIndexSize = buffer.get().toUByte().toInt().also {
                if (it !in VALID_INDEX_SIZES) {
                    throw PmxLoadException("Bad vertex index size: $it, should be ${VALID_INDEX_SIZES.joinToString(", ")}")
                }
            },
            textureIndexSize = buffer.get().toUByte().toInt().also {
                if (it !in VALID_INDEX_SIZES) {
                    throw PmxLoadException("Bad texture index size: $it, should be ${VALID_INDEX_SIZES.joinToString(", ")}")
                }
            },
            materialIndexSize = buffer.get().toUByte().toInt().also {
                if (it !in VALID_INDEX_SIZES) {
                    throw PmxLoadException("Bad material index size: $it, should be ${VALID_INDEX_SIZES.joinToString(", ")}")
                }
            },
            boneIndexSize = buffer.get().toUByte().toInt().also {
                if (it !in VALID_INDEX_SIZES) {
                    throw PmxLoadException("Bad bone index size: $it, should be ${VALID_INDEX_SIZES.joinToString(", ")}")
                }
            },
            morphIndexSize = buffer.get().toUByte().toInt().also {
                if (it !in VALID_INDEX_SIZES) {
                    throw PmxLoadException("Bad morph index size: $it, should be ${VALID_INDEX_SIZES.joinToString(", ")}")
                }
            },
            rigidBodyIndexSize = buffer.get().toUByte().toInt().also {
                if (it !in VALID_INDEX_SIZES) {
                    throw PmxLoadException("Bad rigid body index size: $it, should be ${VALID_INDEX_SIZES.joinToString(", ")}")
                }
            }
        )

        private fun loadString(buffer: ByteBuffer): String {
            if (buffer.remaining() < 4) {
                throw PmxLoadException("No space for string index: want at least 4, but got ${buffer.remaining()}")
            }
            val length = buffer.getInt()
            if (length < 0) {
                throw PmxLoadException("Bad string size, should be at least 0: $length")
            }
            if (buffer.remaining() < length) {
                throw PmxLoadException("No enough data for string: want $length bytes, but only have ${buffer.remaining()} bytes")
            }
            val stringBuffer = buffer.slice(buffer.position(), length).order(ByteOrder.LITTLE_ENDIAN)
            return decoder.decode(stringBuffer).toString().also {
                buffer.position(buffer.position() + length)
            }
        }

        private fun loadHeader(buffer: ByteBuffer): PmxHeader {
            loadSignature(buffer)
            if (buffer.remaining() < 5) {
                throw PmxLoadException("Bad PMX signature")
            }
            val version = buffer.getFloat()
            if (version < 2.0f) {
                throw PmxLoadException("Bad PMX version: at least 2.0, but get $version")
            }
            val globalsCount = buffer.get().toUByte().toInt()
            if (globalsCount < 8) {
                throw PmxLoadException("Bad global count: $globalsCount, at least 8")
            }
            globals = loadGlobal(buffer.slice(buffer.position(), globalsCount).order(ByteOrder.LITTLE_ENDIAN))
            buffer.position(buffer.position() + globalsCount)
            return PmxHeader(
                version = version,
                globals = globals,
                modelNameLocal = loadString(buffer),
                modelNameUniversal = loadString(buffer),
                commentLocal = loadString(buffer),
                commentUniversal = loadString(buffer),
            )
        }

        // Read all vertices from PMX file, and fill them to a vertex attribute array for loading.
        private fun loadVertices(buffer: ByteBuffer) {
            val vertexCount = buffer.getInt()
            if (vertexCount <= 0) {
                throw PmxLoadException("Bad vertex count: $vertexCount, should be greater than 0")
            }

            val additionalVec4Size = globals.additionalVec4Count * 4 * 4
            val boneIndexSize = globals.boneIndexSize

            val outputBuffer =
                ByteBuffer.allocateDirect(VERTEX_ATTRIBUTE_SIZE * vertexCount).order(ByteOrder.nativeOrder())
            var outputPosition = 0
            var inputPosition = buffer.position()

            for (i in 0 until vertexCount) {
                // Read vertex data
                // POSITION_NORMAL_UV_JOINT_WEIGHT
                outputBuffer.put(outputPosition, buffer, inputPosition, BASE_VERTEX_ATTRIBUTE_SIZE)
                outputPosition += BASE_VERTEX_ATTRIBUTE_SIZE
                inputPosition += BASE_VERTEX_ATTRIBUTE_SIZE

                // Skip additionalVec4
                inputPosition += additionalVec4Size

                // Weight deform type
                val weightDeformType = buffer.get(inputPosition).toUByte().toInt()
                inputPosition += 1

                fun readBoneIndex(): Int {
                    val index = when (boneIndexSize) {
                        1 -> buffer.get(inputPosition).toInt()
                        2 -> buffer.getShort(inputPosition).toInt()
                        4 -> buffer.getInt(inputPosition)
                        else -> throw AssertionError()
                    }
                    inputPosition += boneIndexSize
                    return index
                }

                fun readWeight(): Float = buffer.getFloat(inputPosition).also { inputPosition += 4 }
                fun readVector3f(): Vector3f = Vector3f().also {
                    it.set(
                        buffer.getFloat(inputPosition),
                        buffer.getFloat(inputPosition + 4),
                        buffer.getFloat(inputPosition + 8)
                    )
                    inputPosition += 12
                }

                // TODO: keep track of vertices without bone, to exclude non-skinned vertices out
                when (weightDeformType) {
                    // BDEF1
                    0 -> {
                        val index1 = readBoneIndex()
                        outputBuffer.putInt(outputPosition, index1)
                        if (index1 != -1) {
                            outputBuffer.putFloat(outputPosition + 16, 1f)
                        }
                    }
                    // BDEF2
                    1 -> {
                        val index1 = readBoneIndex()
                        val index2 = readBoneIndex()
                        val weight1 = readWeight()
                        outputBuffer.putInt(outputPosition, index1)
                        outputBuffer.putInt(outputPosition + 4, index2)
                        if (index1 != -1) {
                            outputBuffer.putFloat(outputPosition + 16, weight1)
                        }
                        if (index2 != -1) {
                            outputBuffer.putFloat(outputPosition + 20, 1f - weight1)
                        }
                    }
                    // BDEF4, or not really supported QDEF
                    2, 4 -> {
                        val index1 = readBoneIndex()
                        val index2 = readBoneIndex()
                        val index3 = readBoneIndex()
                        val index4 = readBoneIndex()
                        val weight1 = readWeight()
                        val weight2 = readWeight()
                        val weight3 = readWeight()
                        val weight4 = readWeight()
                        outputBuffer.putInt(outputPosition, index1)
                        outputBuffer.putInt(outputPosition + 4, index2)
                        outputBuffer.putInt(outputPosition + 8, index3)
                        outputBuffer.putInt(outputPosition + 12, index4)
                        if (index1 != -1) {
                            outputBuffer.putFloat(outputPosition + 16, weight1)
                        }
                        if (index2 != -1) {
                            outputBuffer.putFloat(outputPosition + 20, weight2)
                        }
                        if (index3 != -1) {
                            outputBuffer.putFloat(outputPosition + 24, weight3)
                        }
                        if (index4 != -1) {
                            outputBuffer.putFloat(outputPosition + 28, weight4)
                        }
                    }

                    3 -> {
                        // SDEF, not really supported, just treat as BDEF2
                        val index1 = readBoneIndex()
                        val index2 = readBoneIndex()
                        val weight1 = readWeight()
                        val c = readVector3f()
                        val r0 = readVector3f()
                        val r1 = readVector3f()
                        outputBuffer.putInt(outputPosition, index1)
                        outputBuffer.putInt(outputPosition + 4, index2)
                        if (index1 != -1) {
                            outputBuffer.putFloat(outputPosition + 16, weight1)
                        }
                        if (index2 != -1) {
                            outputBuffer.putFloat(outputPosition + 20, 1f - weight1)
                        }
                    }
                }
                outputPosition += SKIN_VERTEX_ATTRIBUTE_SIZE

                // Skin edge scale
                inputPosition += 4
            }
            require(outputPosition == outputBuffer.capacity()) { "Bug: Not filled the entire output buffer" }
            vertexBuffer = outputBuffer
            vertices = vertexCount
            buffer.position(inputPosition)
        }

        private fun loadSurfaces(buffer: ByteBuffer) {
            val surfaceCount = buffer.getInt()
            if (surfaceCount % 3 != 0) {
                throw PmxLoadException("Bad surface count: $surfaceCount % 3 != 0")
            }
            val triangleCount = surfaceCount / 3
            val vertexIndexSize = globals.vertexIndexSize
            val indexBufferSize = vertexIndexSize * surfaceCount
            if (buffer.remaining() < indexBufferSize) {
                throw PmxLoadException("Bad surface data: should have $indexBufferSize bytes, but only ${buffer.remaining()} bytes available")
            }

            val outputBuffer = ByteBuffer.allocateDirect(indexBufferSize).order(ByteOrder.nativeOrder())
            // PMX use clockwise indices, but OpenGL use counterclockwise indices, so let's invert the order here.
            when (vertexIndexSize) {
                1 -> {
                    indexBufferType = Accessor.ComponentType.UNSIGNED_BYTE
                    for (i in 0 until triangleCount) {
                        outputBuffer.put(buffer.get())
                        val a = buffer.get()
                        val b = buffer.get()
                        outputBuffer.put(b)
                        outputBuffer.put(a)
                    }
                }

                2 -> {
                    indexBufferType = Accessor.ComponentType.UNSIGNED_SHORT
                    for (i in 0 until triangleCount) {
                        outputBuffer.putShort(buffer.getShort())
                        val a = buffer.getShort()
                        val b = buffer.getShort()
                        outputBuffer.putShort(b)
                        outputBuffer.putShort(a)
                    }
                }

                4 -> {
                    indexBufferType = Accessor.ComponentType.UNSIGNED_INT
                    for (i in 0 until triangleCount) {
                        outputBuffer.putInt(buffer.getInt())
                        val a = buffer.getInt()
                        val b = buffer.getInt()
                        outputBuffer.putInt(b)
                        outputBuffer.putInt(a)
                    }
                }

                else -> throw AssertionError()
            }
            indexBuffer = outputBuffer
            indices = surfaceCount
        }

        private fun loadTextures(buffer: ByteBuffer) {
            val textureCount = buffer.getInt()
            if (textureCount < 0) {
                throw PmxLoadException("Bad texture count: $textureCount, should be at least zero")
            }
            textures = (0 until textureCount).map {
                val pathString = loadString(buffer)
                // For Windows & Unix-like path support
                val pathParts = pathString.split('/', '\\')
                val relativePath = if (pathParts.size == 1) {
                    basePath.fileSystem.getPath(pathParts[0])
                } else {
                    basePath.fileSystem.getPath(pathParts[0], *pathParts.subList(1, pathParts.size).toTypedArray())
                }
                val path = basePath.resolve(relativePath)
                val buffer = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    val size = channel.size()
                    runCatching {
                        channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                    }.getOrNull() ?: run {
                        if (size > 256 * 1024 * 1024) {
                            throw PmxLoadException("Texture too large! Maximum supported is 256M.")
                        }
                        val size = size.toInt()
                        val buffer = ByteBuffer.allocateDirect(size)
                        channel.readAll(buffer)
                        buffer.flip()
                        if (buffer.remaining() != size) {
                            throw PmxLoadException("Not read to texture's end, maybe a bug?")
                        }
                        buffer
                    }
                }
                Texture(
                    name = pathString,
                    bufferView = BufferView(
                        buffer = Buffer(
                            name = "Texture $pathString",
                            buffer = buffer,
                        ),
                        byteLength = buffer.remaining(),
                        byteOffset = 0,
                        byteStride = 0,
                    ),
                    sampler = Texture.Sampler(),
                )
            }
        }

        private fun loadMaterials(buffer: ByteBuffer) {
            val materialCount = buffer.getInt()

            fun loadDrawingFlags(buffer: ByteBuffer): PmxMaterial.DrawingFlags {
                val byte = buffer.get().toUByte().toInt()
                fun loadBitfield(index: Int): Boolean = (byte and (1 shl index)) != 0
                return PmxMaterial.DrawingFlags(
                    noCull = loadBitfield(0),
                    groundShadow = loadBitfield(1),
                    drawShadow = loadBitfield(2),
                    receiveShadow = loadBitfield(3),
                    hasEdge = loadBitfield(4),
                    vertexColor = loadBitfield(5),
                    pointDrawing = loadBitfield(6),
                    lineDrawing = loadBitfield(7),
                )
            }

            fun loadTextureIndex(buffer: ByteBuffer): Int = when (globals.textureIndexSize) {
                1 -> buffer.get().toInt()
                2 -> buffer.getShort().toInt()
                4 -> buffer.getInt()
                else -> throw AssertionError()
            }

            materials = (0 until materialCount).map {
                PmxMaterial(
                    nameLocal = loadString(buffer),
                    nameUniversal = loadString(buffer),
                    diffuseColor = loadRgbaColor(buffer),
                    specularColor = loadRgbColor(buffer),
                    specularStrength = buffer.getFloat(),
                    ambientColor = loadRgbColor(buffer),
                    drawingFlags = loadDrawingFlags(buffer),
                    edgeColor = loadRgbaColor(buffer),
                    edgeScale = buffer.getFloat(),
                    textureIndex = loadTextureIndex(buffer),
                    environmentIndex = loadTextureIndex(buffer),
                    environmentBlendMode = when (val mode = buffer.get().toInt()) {
                        0 -> PmxMaterial.EnvironmentBlendMode.DISABLED
                        1 -> PmxMaterial.EnvironmentBlendMode.MULTIPLY
                        2 -> PmxMaterial.EnvironmentBlendMode.ADDICTIVE
                        3 -> PmxMaterial.EnvironmentBlendMode.ADDITIONAL_VEC4
                        else -> throw PmxLoadException("Unsupported environment blend mode: $mode")
                    },
                    toonReference = when (val type = buffer.get().toInt()) {
                        0 -> PmxMaterial.ToonReference.Texture(index = loadTextureIndex(buffer))
                        1 -> PmxMaterial.ToonReference.Internal(index = buffer.get().toUByte())
                        else -> throw PmxLoadException("Unsupported toon reference: $type")
                    },
                    metadata = loadString(buffer),
                    surfaceCount = buffer.getInt().also {
                        if (it < 0) {
                            throw PmxLoadException("Material with $it vertices. Should be greater than zero.")
                        }
                        if (it % 3 != 0) {
                            throw PmxLoadException("Material with $it % 3 != 0 vertices.")
                        }
                    },
                )
            }
        }

        private fun loadBones(buffer: ByteBuffer) {
            val boneCount = buffer.getInt()
            if (boneCount < 0) {
                throw PmxLoadException("Bad PMX model: bones count less than zero")
            }

            fun loadBoneIndex(buffer: ByteBuffer): Int = when (globals.boneIndexSize) {
                1 -> buffer.get().toInt()
                2 -> buffer.getShort().toInt()
                4 -> buffer.getInt()
                else -> throw AssertionError()
            }

            fun loadBoneFlags(buffer: ByteBuffer): PmxBone.Flags {
                val flags = buffer.getShort().toInt()
                fun loadBitfield(index: Int): Boolean = (flags and (1 shl index)) != 0
                return PmxBone.Flags(
                    indexedTailPosition = loadBitfield(0),
                    rotatable = loadBitfield(1),
                    translatable = loadBitfield(2),
                    isVisible = loadBitfield(3),
                    enabled = loadBitfield(4),
                    ik = loadBitfield(5),
                    inheritRotation = loadBitfield(8),
                    inheritTranslation = loadBitfield(9),
                    fixedAxis = loadBitfield(10),
                    localCoordinate = loadBitfield(11),
                    physicsAfterDeform = loadBitfield(12),
                    externalParentDeform = loadBitfield(13),
                )
            }

            fun loadBone(buffer: ByteBuffer): PmxBone {
                val nameLocal = loadString(buffer)
                val nameUniversal = loadString(buffer)
                val position = loadVector3f(buffer)
                val parentBoneIndex = loadBoneIndex(buffer)
                val layer = buffer.getInt()
                val flags = loadBoneFlags(buffer)
                val tailPosition = if (flags.indexedTailPosition) {
                    PmxBone.TailPosition.Indexed(loadBoneIndex(buffer))
                } else {
                    PmxBone.TailPosition.Scalar(loadVector3f(buffer))
                }
                val inheritParent = if (flags.inheritRotation || flags.inheritTranslation) {
                    Pair(loadBoneIndex(buffer), buffer.getFloat())
                } else {
                    null
                }
                val axisDirection = if (flags.fixedAxis) {
                    loadVector3f(buffer)
                } else {
                    null
                }
                val localCoordinate = if (flags.localCoordinate) {
                    PmxBone.LocalCoordinate(loadVector3f(buffer), loadVector3f(buffer))
                } else {
                    null
                }
                val externalParentIndex = if (flags.externalParentDeform) {
                    loadBoneIndex(buffer)
                } else {
                    null
                }
                val ikData = if (flags.ik) {
                    val targetIndex = loadBoneIndex(buffer)
                    val loopCount = buffer.getInt()
                    val limitRadian = buffer.getFloat()
                    val linkCount = buffer.getInt()
                    val links = (0 until linkCount).map {
                        val index = loadBoneIndex(buffer)
                        val limits = if (buffer.get() != 0.toByte()) {
                            PmxBone.IkLink.Limits(
                                limitMin = loadVector3f(buffer),
                                limitMax = loadVector3f(buffer),
                            )
                        } else {
                            null
                        }
                        PmxBone.IkLink(
                            index = index,
                            limits = limits,
                        )
                    }
                    PmxBone.IkData(
                        targetIndex = targetIndex,
                        loopCount = loopCount,
                        limitRadian = limitRadian,
                        links = links,
                    )
                } else {
                    null
                }
                return PmxBone(
                    nameLocal = nameLocal,
                    nameUniversal = nameUniversal,
                    position = position,
                    parentBoneIndex = parentBoneIndex.takeIf { it >= 0 },
                    layer = layer,
                    flags = flags,
                    tailPosition = tailPosition,
                    inheritParentIndex = inheritParent?.first,
                    inheritParentInfluence = inheritParent?.second,
                    axisDirection = axisDirection,
                    localCoordinate = localCoordinate,
                    externalParentIndex = externalParentIndex,
                    ikData = ikData,
                )
            }

            bones = (0 until boneCount).map { index ->
                loadBone(buffer).also { bone ->
                    bone.parentBoneIndex?.let { parentBoneIndex ->
                        childBoneMap.getOrPut(parentBoneIndex) { mutableListOf() }.add(index)
                    } ?: run {
                        rootBones.add(index)
                    }
                }
            }
        }

        fun load(buffer: ByteBuffer): ModelFileLoader.Result {
            val header = loadHeader(buffer)
            loadVertices(buffer)
            loadSurfaces(buffer)
            loadTextures(buffer)
            loadMaterials(buffer)
            loadBones(buffer)

            val modelId = UUID.randomUUID()
            val rootNodes = mutableListOf<Node>()
            var nextNodeId = 0

            val jointIds = mutableMapOf<Int, NodeId>()
            fun addBone(index: Int, parentPosition: Vector3f? = null): Node {
                val bone = bones[index]
                var nodeIndex = nextNodeId++
                val nodeId = NodeId(modelId, nodeIndex)
                jointIds[index] = nodeId
                val children = childBoneMap[index]?.map { addBone(it, bone.position) } ?: listOf()
                return Node(
                    name = bone.nameLocal,
                    id = nodeId,
                    children = children,
                    transform = NodeTransform.Decomposed(
                        translation = Vector3f().set(bone.position).also {
                            if (parentPosition != null) {
                                it.sub(parentPosition)
                            }
                        },
                    )
                )
            }
            rootBones.forEach { index ->
                rootNodes.add(addBone(index))
            }

            val skin = Skin(
                name = "PMX skin",
                joints = (0 until bones.size).map { jointIds[it]!! },
                inverseBindMatrices = bones.map { Matrix4f().translation(it.position).invertAffine() },
                jointHumanoidTags = bones.map {
                    HumanoidTag.fromPmxJapanese(it.nameLocal) ?: HumanoidTag.fromPmxEnglish(
                        it.nameUniversal
                    )
                },
            )

            val vertexBuffer = Buffer(
                name = "Vertex Buffer",
                buffer = vertexBuffer
            )
            val vertexBufferView = BufferView(
                buffer = vertexBuffer,
                byteLength = vertices * VERTEX_ATTRIBUTE_SIZE,
                byteOffset = 0,
                byteStride = VERTEX_ATTRIBUTE_SIZE,
            )
            val indexBuffer = Buffer(
                name = "Index Buffer",
                buffer = indexBuffer,
            )
            val indexBufferView = BufferView(
                buffer = indexBuffer,
                byteLength = indices * indexBufferType.byteLength,
                byteOffset = 0,
                byteStride = 0,
            )

            var indexOffset = 0
            materials.forEach { pmxMaterial ->
                val nodeId = nextNodeId++
                val material = Material.Unlit(
                    name = pmxMaterial.nameLocal,
                    baseColor = pmxMaterial.diffuseColor,
                    baseColorTexture = pmxMaterial.textureIndex.takeIf { it >= 0 }?.let {
                        textures.getOrNull(it) ?: throw PmxLoadException("Bad texture index: $it")
                    }?.let {
                        Material.TextureInfo(it)
                    },
                    doubleSided = pmxMaterial.drawingFlags.noCull,
                )
                Node(
                    name = "Node for material ${pmxMaterial.nameLocal}",
                    id = NodeId(modelId, nodeId),
                    skin = skin,
                    mesh = Mesh(
                        primitives = listOf(
                            Primitive(
                                mode = Primitive.Mode.TRIANGLES,
                                material = material,
                                attributes = Primitive.Attributes(
                                    position = Accessor(
                                        bufferView = vertexBufferView,
                                        byteOffset = 0,
                                        componentType = Accessor.ComponentType.FLOAT,
                                        normalized = false,
                                        count = vertices,
                                        type = Accessor.AccessorType.VEC3,
                                    ),
                                    normal = Accessor(
                                        bufferView = vertexBufferView,
                                        byteOffset = 3 * 4,
                                        componentType = Accessor.ComponentType.FLOAT,
                                        normalized = false,
                                        count = vertices,
                                        type = Accessor.AccessorType.VEC3,
                                    ),
                                    texcoords = listOf(
                                        Accessor(
                                            bufferView = vertexBufferView,
                                            byteOffset = (3 + 3) * 4,
                                            componentType = Accessor.ComponentType.FLOAT,
                                            normalized = false,
                                            count = vertices,
                                            type = Accessor.AccessorType.VEC2,
                                        )
                                    ),
                                    joints = listOf(
                                        Accessor(
                                            bufferView = vertexBufferView,
                                            byteOffset = (3 + 3 + 2) * 4,
                                            componentType = Accessor.ComponentType.UNSIGNED_INT,
                                            normalized = false,
                                            count = vertices,
                                            type = Accessor.AccessorType.VEC4,
                                        )
                                    ),
                                    weights = listOf(
                                        Accessor(
                                            bufferView = vertexBufferView,
                                            byteOffset = (3 + 3 + 2 + 4) * 4,
                                            componentType = Accessor.ComponentType.FLOAT,
                                            normalized = false,
                                            count = vertices,
                                            type = Accessor.AccessorType.VEC4,
                                        )
                                    )
                                ),
                                indices = Accessor(
                                    bufferView = indexBufferView,
                                    byteOffset = indexOffset * indexBufferType.byteLength,
                                    componentType = indexBufferType,
                                    normalized = false,
                                    count = pmxMaterial.surfaceCount,
                                    type = Accessor.AccessorType.SCALAR,
                                )
                            )
                        ),
                    )
                ).also {
                    rootNodes.add(it)
                    indexOffset += pmxMaterial.surfaceCount
                }
            }

            return ModelFileLoader.Result(
                metadata = Metadata(
                    title = header.modelNameLocal,
                    titleUniversal = header.modelNameUniversal,
                    comment = header.commentLocal,
                    commentUniversal = header.commentUniversal,
                ),
                scene = Scene(
                    nodes = rootNodes,
                    skins = listOf(skin),
                    initialTransform = NodeTransform.Decomposed(
                        scale = Vector3f(0.1f),
                    ),
                ),
                animations = listOf(),
            )
        }
    }

    override fun load(path: Path, basePath: Path) =
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val fileSize = channel.size()
            val buffer = runCatching {
                channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize)
            }.getOrNull() ?: run {
                if (fileSize > 32 * 1024 * 1024) {
                    throw PmxLoadException("PMX model size too large: maximum allowed is 32M, current is $fileSize")
                }
                val fileSize = fileSize.toInt()
                val buffer = ByteBuffer.allocate(fileSize)
                channel.readAll(buffer)
                buffer.flip()
                if (channel.position() != fileSize.toLong()) {
                    throw PmxLoadException("Not read to file's end, maybe a bug?")
                }
                buffer
            }
            val context = Context(basePath)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            context.load(buffer)
        }
}