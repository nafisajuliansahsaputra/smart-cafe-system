import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { staffDirectory, staffIdentity, localTime } from "@/lib/staff-server";
import { setStaffEnabled } from "./actions";

export const dynamic="force-dynamic";

const messages:Record<string,string>={
  updated:"Status staf diperbarui. Sesi lama akun yang dinonaktifkan telah dicabut.",
  conflict:"Status akun berubah. Segarkan daftar sebelum mengulangi tindakan.",
  forbidden:"Perubahan ditolak. Akun manager dan admin dilindungi.",
  offline:"Server tidak dapat mengonfirmasi perubahan. Periksa status terbaru.",
  invalid:"Konfirmasi, akun, atau alasan tindakan tidak valid.",
};

export default async function TeamPage({searchParams}:{
  searchParams:Promise<{page?:string;notice?:string}>;
}) {
  const identity=await staffIdentity();
  if(identity.kind==="signed-out")redirect("/staff/login?status=expired");
  if(identity.kind==="unavailable")return <main className="staff-shell">
    <h1>Manajemen staf tidak tersedia</h1>
    <p>Identitas staf belum dapat diverifikasi. Tidak ada perubahan akun.</p>
    <Link href="/staff">Kembali</Link></main>;
  if(!identity.staff.roles.some(r=>r==="MANAGER"||r==="ADMIN"))notFound();
  const params=await searchParams;
  const index=typeof params.page==="string" && /^(0|[1-9]\d{0,3})$/.test(params.page)
    ?Math.min(2000,Number(params.page)):0;
  const list=await staffDirectory(index);
  if(!list.ok && list.status===401)redirect("/staff/login?status=expired");
  return <main className="staff-shell staff-wide">
    <header className="staff-header"><div>
      <div className="staff-eyebrow">SMART CAFE / ADMIN</div>
      <h1>Manajemen staf</h1>
      <p className="staff-muted">Aktifkan atau nonaktifkan akses staf biasa.
        Akun manager dan admin dilindungi dari perubahan status melalui layar ini.</p>
    </div></header>
    <nav className="staff-breadcrumb">
      <Link href="/staff">Ruang staf</Link><span>/</span><strong>Staf</strong>
    </nav>
    {typeof params.notice==="string" && Object.hasOwn(messages,params.notice) &&
      <p className="staff-notice" role="status">{messages[params.notice]}</p>}
    {!list.ok && <section className="staff-panel" role="alert">
      <h2>Daftar staf tidak dapat dimuat</h2>
      <p>Jangan mengasumsikan akun aktif atau nonaktif tanpa status dari server.</p>
    </section>}
    {list.ok && <section className="staff-panel">
      <h2>Daftar akun</h2>
      <p className="staff-muted">Halaman {list.data.page+1}. Maksimal 25 akun per halaman.</p>
      {list.data.items.length===0 && <p>Tidak ada akun pada halaman ini.</p>}
      <div className="staff-task-grid">
        {list.data.items.map(staff=>{
          const privileged=staff.roles.some(r=>r==="ADMIN"||r==="MANAGER");
          const self=staff.id===identity.staff.id;
          return <article className="staff-panel staff-task-card" key={staff.id}>
            <h3>{staff.login}</h3>
            <p>Status: <strong>{staff.enabled?"AKTIF":"NONAKTIF"}</strong></p>
            <p>Peran: {staff.roles.join(", ")||"Belum ditetapkan"}</p>
            <p className="staff-muted">Dibuat: {localTime(staff.createdAt)} WIB</p>
            {privileged||self?(
              <p className="staff-muted">Akun dilindungi. Hubungi pengelola untuk prosedur pemulihan.</p>
            ):(
              <form className="staff-form" action={setStaffEnabled}>
                <input type="hidden" name="staffId" value={staff.id}/>
                <input type="hidden" name="expectedEnabled" value={String(staff.enabled)}/>
                <label htmlFor={"reason-"+staff.id}>Alasan perubahan (wajib)</label>
                <input id={"reason-"+staff.id} name="reason" minLength={5} maxLength={300}
                  required placeholder="Contoh: pergantian shift atau akses disetujui"/>
                <label className="staff-check">
                  <input type="checkbox" name="confirm" value="yes" required/>
                  Saya telah memverifikasi akun dan tindakan ini
                </label>
                <button className={staff.enabled?"staff-danger":"staff-primary"} type="submit">
                  {staff.enabled?"Nonaktifkan akses":"Aktifkan akses"}
                </button>
              </form>
            )}
          </article>;
        })}
      </div>
      <nav className="staff-breadcrumb" aria-label="Halaman staf">
        {index>0&&<Link href={"/staff/team?page="+(index-1)}>Sebelumnya</Link>}
        {list.data.hasNext&&<Link href={"/staff/team?page="+(index+1)}>Selanjutnya</Link>}
      </nav>
    </section>}
    <p className="staff-footnote">
      Menonaktifkan staf langsung mencabut sesi aktif, tetapi tidak menghapus transaksi,
      log audit atau kepemilikan kunjungan. Tidak ada perubahan peran atau kata sandi di fitur ini.
      Konfirmasi status terbaru sebelum membuat perubahan berikutnya.
    </p>
  </main>;
}
