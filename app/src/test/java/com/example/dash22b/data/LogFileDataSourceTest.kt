package com.example.dash22b.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

class LogFileDataSourceTest {

    // 1. Fake AssetLoader
    class FakeAssetLoader(private val fileContent: Map<String, String>) : AssetLoader {
        override fun open(fileName: String): InputStream {
            val content =
                fileContent[fileName] ?: throw IllegalArgumentException("File not found: $fileName")
            return ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `test parsing log file extracts initial gauge configs`() = runBlocking {
        // 2. Mock Data
        
        // Minimal Parameter Registry CSV
        val registryCsv = """
            Id,Type,Unit,Name,Description,Min Expected,Typical Expected,Max Expected,Accessport Monitor
            P1,Std,rpm,Engine Speed,RPM,0,1000,8000,RPM
            P2,Std,km/h,Vehicle Speed,Speed,0,50,250,Vehicle Speed
            P3,Std,psi,Manifold Relative Pressure,Boost,0,10,20,Boost
            P4,Std,V,Battery Voltage,Volts,10,12,15,Battery Voltage
            P5,Std,ms,Injector Pulse Width,Pulse,0,2,20,Inj Pulse Width
            P6,Std,F,Coolant Temperature,Coolant,0,180,220,Coolant Temp
            P7,Std,deg,Ignition Timing,Ignition,0,10,50,Ignition Timing
            P8,Std,%,Injector Duty Cycle,Duty,0,10,80,Inj Duty Cycle
            P9,Std,F,Intake Temperature,IAT,0,80,120,Intake Temp
            P10,Std,AFR,Comm Fuel Final,AFR,10,14.7,20,Comm Fuel Final
            P11,Std,g/s,Mass Airflow,MAF,0,50,300,Mass Airflow
        """.trimIndent()

        // Mock Log File (Headers matching Accessport Monitor names from Registry + "Time")
        // Note: Headers order doesn't matter, but values must match
        val logCsv = """
            Time,RPM,Vehicle Speed,Boost,Battery Voltage,Inj Pulse Width,Coolant Temp,Ignition Timing,Inj Duty Cycle,Intake Temp,Comm Fuel Final,Mass Airflow
            0.0,3200,60,14.5,13.8,2.5,190,12.5,15.5,85,14.2,45.5
        """.trimIndent()

        val fakeFiles = mapOf(
            "2005_STi_SSM_Parameters_Ranges.csv" to registryCsv,
            "sampleLogs/20251024184038-replaced-o2-sensor.csv" to logCsv
        )

        val assetLoader = FakeAssetLoader(fakeFiles)
        val parameterRegistry = ParameterRegistry.fromCsv(assetLoader)
        val dataSource = LogFileDataSource(assetLoader, parameterRegistry)

        // 3. Execution
        val engineData = dataSource.getEngineData().first()

        // 4. Assertions based on "initialConfigs" from DashboardScreen
        // GaugeConfig(0, "RPM")
        assertEquals(3200f, engineData.values["RPM"]?.value) 
        
        // GaugeConfig(1, "Vehicle Speed")
        assertEquals(60f, engineData.values["Vehicle Speed"]?.value)

        // GaugeConfig(2, "Boost")
        // Logic in DataSource now just parses. Header was "Boost". Unit empty? 
        // Mock CSV has "Boost" as header.
        // My code regex: `(.*)\s*\((.*?)\)`. Matching "Boost" -> match null?
        // Fallback `trim()`. Unit "".
        assertEquals(14.5f, engineData.values["Boost"]?.value)

        // GaugeConfig(3, "Battery Voltage")
        assertEquals(13.8f, engineData.values["Battery Voltage"]?.value)

        // GaugeConfig(4, "Inj Pulse Width")
        assertEquals(2.5f, engineData.values["Inj Pulse Width"]?.value)

        // GaugeConfig(5, "Coolant Temp")
        assertEquals(190f, engineData.values["Coolant Temp"]?.value)

        // GaugeConfig(6, "Ignition Timing")
        assertEquals(12.5f, engineData.values["Ignition Timing"]?.value)

        // GaugeConfig(7, "Inj Duty Cycle")
        assertEquals(15.5f, engineData.values["Inj Duty Cycle"]?.value)

        // GaugeConfig(8, "Intake Temp")
        assertEquals(85f, engineData.values["Intake Temp"]?.value)

        // GaugeConfig(9, "Comm Fuel Final")
        assertEquals(14.2f, engineData.values["Comm Fuel Final"]?.value)

        // GaugeConfig(10, "Mass Airflow")
        assertEquals(45.5f, engineData.values["Mass Airflow"]?.value)
        
        // Verify Common Field Mapping (Backward Compatibility)
        // rpm is ValueWithUnit.
        assertEquals("Common Field RPM", 3200, engineData.rpm.value.toInt())
        assertEquals("Common Field Speed", 60, engineData.speed.value.toInt())
    }

    class DirAssetLoader(val dir: File) : AssetLoader {
        override fun open(fileName: String): InputStream {
            return File(dir, fileName).inputStream()
        }
    }

    @Test
    fun testFindParams() {
        val assetLoader = DirAssetLoader(File("src/main/assets"))
        val parameterRegistry = ParameterRegistry.fromCsv(assetLoader)
        val dataSource = LogFileDataSource(assetLoader, parameterRegistry)
        val headers2defs = sortedMapOf<String, Pair<String, ParameterDefinition>>()
        File("src/main/assets/sampleLogs").listFiles().orEmpty().filter { it.name.endsWith(".csv") }
            .forEach {
                println(it.name)
                val headers = it.reader(Charsets.ISO_8859_1).useLines { lines -> lines.first() }
                println(headers)
                headers.split(",").forEach { header ->
                    println(header)
                    val (hdr, unit, def) = dataSource.findParameterDefinition(header)

                    if (hdr != "Time" && !hdr.contains("AP Info")) {
                        assertNotNull(def)
                        if (!headers2defs.containsKey(hdr)) {
                            println("Header: $hdr, Unit: $unit, ${def!!.unit}, Def: ${def.name}")
                            headers2defs[hdr] = unit to def
                        }
                    }
                }
            }
        headers2defs.forEach { (hdr, pair) ->
            val (hdrUnit, def) = pair
            println("$hdr $hdrUnit-${def.unit} ${def.name}")
        }

    }

    @Test
    fun testParseAll() {
        val assetLoader = DirAssetLoader(File("src/main/assets"))
        val parameterRegistry = ParameterRegistry.fromCsv(assetLoader)
        val dataSource = LogFileDataSource(assetLoader, parameterRegistry)
        File("src/main/assets/sampleLogs").listFiles().orEmpty().filter { it.name.endsWith(".csv") }.forEach {
            println(it.name)
            val list = runBlocking { dataSource.parseLogFile("sampleLogs/${it.name}").toList() }
            println("${it.name} ${list.size}")
            val ed = list.first()
            ed.values.forEach { (k, v) ->
                val def = parameterRegistry.getDefinition(k)!!
                val converted = UnitConverter.convert(v.value, v.unit, def.unit)
                println("$k ${v.value}${v.unit} == $converted${def.unit} ${def.name}")

            }
        }
    }
}
