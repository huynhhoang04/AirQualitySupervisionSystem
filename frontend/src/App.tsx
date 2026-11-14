import { Navigate, Route, Routes, useParams } from "react-router-dom";

import { AppShell } from "./components/AppShell";
import { AiCenterPage } from "./pages/AiCenterPage";
import { AuditPage } from "./pages/AuditPage";
import { ConnectPage } from "./pages/ConnectPage";
import { DevicesPage } from "./pages/DevicesPage";
import { EvaluationPage } from "./pages/EvaluationPage";
import { GuidePage } from "./pages/GuidePage";
import { RoomPage } from "./pages/RoomPage";
import { RoomSettingsPage } from "./pages/RoomSettingsPage";
import { RoomsPage } from "./pages/RoomsPage";

/**
 * Desktop-first routes. The connection wizard and the guide stay outside the shell: they are focus
 * flows, not dashboard screens. Every other screen shares the rail and header.
 */
export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<RoomsPage />} />
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="/ai" element={<AiCenterPage />} />
        <Route path="/evaluation" element={<EvaluationPage />} />
        <Route path="/audit" element={<AuditPage />} />
        <Route path="/room/:deviceId" element={<RoomRoute />} />
        <Route path="/room/:deviceId/settings" element={<SettingsRoute />} />
      </Route>
      <Route path="/connect" element={<ConnectPage />} />
      <Route path="/guide" element={<GuidePage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function RoomRoute() {
  const { deviceId = "" } = useParams();
  return <RoomPage deviceId={deviceId} />;
}

function SettingsRoute() {
  const { deviceId = "" } = useParams();
  return <RoomSettingsPage deviceId={deviceId} />;
}
