package com.example.dash22b.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class LogFileDataSourceTest {

    // 1. Fake AssetLoader
    class FakeAssetLoader(private val fileContent: Map<String, String>) : AssetLoader {
        override fun open(fileName: String): InputStream {
            val content = fileContent[fileName] ?: throw IllegalArgumentException("File not found: $fileName")
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
        val dataSource = LogFileDataSource(assetLoader)

        // 3. Execution
        val engineData = dataSource.getEngineData().first()

        // 4. Assertions based on "initialConfigs" from DashboardScreen
        // GaugeConfig(0, "RPM")
        assertEquals(3200f, engineData.values["RPM"]) 
        
        // GaugeConfig(1, "Vehicle Speed")
        assertEquals(60f, engineData.values["Vehicle Speed"])

        // GaugeConfig(2, "Boost")
        // Logic in DataSource applies conversion for "psi" -> bar: val * 0.0689476f
        // 14.5 * 0.0689476 \u2248 1.0
        // Wait, Header in my mock is plain "Boost". DataSource checks header string for "psi" or "kPa"
        // In my mock `logCsv`, header is just "Boost". So no conversion will happen.
        // Let's verify raw value first or update header to test conversion.
        // User asked to test extraction, so raw 14.5 is expected if no unit in header.
        assertEquals("Boost check", 14.5f, engineData.values["Boost"])

        // GaugeConfig(3, "Battery Voltage")
        assertEquals(13.8f, engineData.values["Battery Voltage"])

        // GaugeConfig(4, "Inj Pulse Width")
        assertEquals(2.5f, engineData.values["Inj Pulse Width"])

        // GaugeConfig(5, "Coolant Temp")
         // Logic checks if header has "(F)" to convert to C. Here it doesn't.
        assertEquals(190f, engineData.values["Coolant Temp"])

        // GaugeConfig(6, "Ignition Timing")
        assertEquals(12.5f, engineData.values["Ignition Timing"])

        // GaugeConfig(7, "Inj Duty Cycle")
        assertEquals(15.5f, engineData.values["Inj Duty Cycle"])

        // GaugeConfig(8, "Intake Temp")
        assertEquals(85f, engineData.values["Intake Temp"])

        // GaugeConfig(9, "Comm Fuel Final")
        assertEquals(14.2f, engineData.values["Comm Fuel Final"])

        // GaugeConfig(10, "Mass Airflow")
        assertEquals(45.5f, engineData.values["Mass Airflow"])
        
        // Verify Common Field Mapping (Backward Compatibility)
        assertEquals("Common Field RPM", 3200, engineData.rpm)
        assertEquals("Common Field Speed", 60, engineData.speed)
    }
}
